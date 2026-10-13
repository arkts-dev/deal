package deal.test;

import deal.ast.FunctionDeclaration;
import deal.ast.StatementNode;
import deal.checker.BuiltinErrorDeclaration;
import deal.checker.Symbol;
import deal.checker.SymbolTable;
import deal.codegen.Backend;
import deal.codegen.SharedEmissionFailure;
import deal.codegen.jvm.JvmSemanticEmitter;
import deal.codegen.lua.FfiEmissionInput;
import deal.codegen.lua.LuaSemanticEmitter;
import deal.diagnostics.CompilerDiagnostic;
import deal.diagnostics.DiagnosticRange;
import deal.diagnostics.RangeOrigin;
import deal.distribution.DistributionHome;
import deal.ffi.FfiForwardBindings;
import deal.ffi.FfiGeneratedModule;
import deal.ffi.FfiImportedFunctionReference;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.publication.PublicationStager;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ContainerPayloadDescriptors;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.CanonicalJson;
import deal.semantic.ir.AnchorId;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryId;
import deal.semantic.ir.DefaultOwner;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.ExternalAsyncLink;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.OpId;
import deal.semantic.ir.ProjectInterfaceIndex;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SourceOrigin;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.SourceSpan;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;
import deal.types.Type;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

public class ProductionProjectEmissionTest {

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
    // Fixtures (compiled for their checked-project facts; the phase-4 arm
    // is not the fixture compile's subject, because this unit drives
    // ProductionProjectEmission directly)
    // =========================================================================

    private static final ModuleId LIB = new ModuleId("lib");
    private static final ModuleId HOST_CFG = new ModuleId("host.cfg");
    private static final String HOST_CFG_SPECIFIER = "host/cfg";

    /** The extern-C declaration module of the admission probe. */
    private static final ModuleId NATIVE_MATH = new ModuleId("native.math");
    private static final String EXTERN_C_SPECIFIER = "native/math";

    private static final String LIB_SOURCE = """
        export function add(a: int, b: int): int {
          return a + b;
        }

        export function twice(x: int): int {
          return x * 2;
        }
        """;

    /** The covered two-module fixture: a COMPILED import, no call. */
    private static final String APP_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          return null;
        }

        export function label(): string {
          return "app";
        }
        """;

    /** The one-main probe: the entry main publishes one effect byte line. */
    private static final String PROBE_APP_SOURCE = """
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
     * The two STDLIB imports of the catalog-miss probe: their distinct
     * spans prove the guard carries the span of the import that recorded
     * the failing catalog entry, never another matching import's.
     */
    private static final String TWO_STDLIB_APP_SOURCE = """
        import * as console from "std/console"
        import * as str from "std/string"

        export function main(): null {
          console.log("PROBE|STDLIB-IMPORT");
          return null;
        }
        """;

    /**
     * The declaration-origin fixture: one plain function {@code f} whose
     * checked signature the declaration-origin probe corrupts, plus the
     * production entry.
     */
    private static final String DECLARATION_ORIGIN_APP_SOURCE = """
        export function main(): null {
          return null;
        }

        function f(): int {
          return 41;
        }
        """;

    /**
     * The bytes-bearing fixture: the lowering fails CONSTRUCT_UNLOWERED.
     */
    private static final String BYTES_APP_SOURCE = """
        export function main(): null {
          let n: int = 3;
          let b: bytes = bytes(n);
          return null;
        }
        """;

    /** The cross-module sync call: the realized external-call arm. */
    private static final String SYNC_CALL_APP_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          let x: int = lib.add(1, 2);
          return null;
        }
        """;

    /** The HOST declaration: two function exports in declaration order. */
    private static final String HOST_DECLARATION = """
        export function version(): int;
        export function describe(s: string): string;
        """;

    /** The host-import fixture: the import is never called or constructed. */
    private static final String HOST_APP_SOURCE = """
        import * as cfg from "host/cfg"

        export function main(): null {
          return null;
        }
        """;

    /**
     * The exported class with one field default: the CLASS_DEFAULT op the
     * rejected-op emission boundaries identify.
     */
    private static final String CLASS_DEFAULT_APP_SOURCE = """
        export function main(): null {
          return null;
        }

        export class Point {
          x: int = 42;
        }
        """;

    /** The extern-C declaration: the narrowed guard's remaining shape. */
    private static final String EXTERN_C_DECLARATION = """
        // @extern-c
        export function nativeAdd(a: int, b: int): int;
        """;

    /** The extern-C import fixture: the import is never called. */
    private static final String EXTERN_C_APP_SOURCE = """
        import * as native from "native/math"

        export function main(): null {
            return null;
        }
        """;

    /** The async library of the cross-module async fixture. */
    private static final String ASYNC_LIB_SOURCE = """
        export async function getValue(): int {
          return 42;
        }
        """;

    /**
     * The cross-module async call: the exported worker really executes the
     * caller's alias-token linkage (the callee is a non-entry module).
     */
    private static final String CROSS_ASYNC_APP_SOURCE = """
        import * as lib from "./lib"

        export function main(): null {
          return null;
        }

        export async function worker(): int {
          return await lib.getValue();
        }
        """;

    /**
     * The same-module async call: accepted, emitted, and executable. The
     * awaiting call sits in an exported zero-arity async function so the
     * runner can invoke it through the artifact's published surface and
     * really execute the ASYNC_START(DEAL_BODY)/AWAIT path.
     */
    private static final String SAME_ASYNC_APP_SOURCE = """
        export function main(): null {
          return null;
        }

        async function compute(): int {
          return 1;
        }

        export async function worker(): int {
          return await compute();
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
        DistributionHome distributionHome) {
    }

    /**
     * The recorded invocation of the fixture compile: the record selects
     * no arm (phase 4 always runs the one production arm), and the fixture
     * compile only contributes the checked-project facts this unit drives
     * ProductionProjectEmission with.
     */
    private static CompilerInvocation fixtureInvocation() {
        return CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.V1_2_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    /** The release-owned production invocation this unit is driven with. */
    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Fixture compileProject(Map<String, String> sources,
            Map<String, String> externals) throws Exception {
        Path root = Files.createTempDirectory("production-arm-fixture");
        Path src = root.resolve("src");
        for (Map.Entry<String, String> source : sources.entrySet()) {
            writeFileIn(root, source.getKey(), source.getValue());
        }
        Map<String, String> resolvedExternals = new LinkedHashMap<>();
        for (Map.Entry<String, String> external : externals.entrySet()) {
            resolvedExternals.put(external.getKey(),
                root.resolve(external.getValue()).toAbsolutePath().toString());
        }
        Path entry = src.resolve("app.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry, output,
            false, false, false, false, Backend.LUAJIT, resolvedExternals,
            List.of(src.toAbsolutePath()), null, null, fixtureInvocation());
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        // The fixture compile's subject here is the pre-phase-4 fact set:
        // the checked project, the manifests, and the declaration surface.
        // A construct outside the landed lowering coverage fails the fixture
        // compile closed in phase 4 with its named E6005, which is the very
        // outcome this unit drives directly below.
        boolean fixtureFacts = built != null && built.input() != null
                && built.index() != null && !built.hasErrors()
                && manifests != null && manifests.manifests() != null
                && orchestrator.hostDeclarationSurface() != null;
        boolean honestFailClosed = compiled || orchestrator.diagnostics().stream()
            .allMatch(d -> "E6005".equals(d.code()));
        if (!fixtureFacts || !honestFailClosed) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the fixture project did not build: " + detail
                + " / " + orchestrator.diagnostics());
        }
        HostDeclarationSurface surface = orchestrator.hostDeclarationSurface();
        Map<ModuleId, FfiGeneratedModule> externCModules = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externCModules.put(new ModuleId(generated.getKey()), generated.getValue());
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule : surface.moduleIds()) {
            String specifier = null;
            for (Map.Entry<String, String> external : externals.entrySet()) {
                if (external.getKey().replace('/', '.').equals(declarationModule.path())) {
                    specifier = external.getKey();
                    break;
                }
            }
            identities.put(declarationModule, specifier == null
                ? IdentityTestFixtures.moduleIdentityOf(declarationModule.path())
                : new CanonicalModuleIdentity.ExternalModule(specifier));
        }
        return new Fixture(root, built.input(), built.index(), manifests.manifests(),
            surface, externCModules, identities,
            DistributionHome.forManifestDirectory(src.toString()));
    }

    private static Fixture twoModuleFixture(String appSource) throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/lib.deal", LIB_SOURCE,
                "src/app.deal", appSource)), Map.of());
    }

    private static Fixture bytesFixture() throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/app.deal", BYTES_APP_SOURCE)), Map.of());
    }

    /** The fail-closed source: a nested function declaration (CALLS family). */
    private static final String NESTED_DECLARATION_APP_SOURCE = """
        export function main(): null {
          function inner(): int { return 1; }
          let x: int = inner();
          return null
        }
        """;

    /**
     * The still-fail-closed source: an arity-extended adapter call whose
     * source is a non-identifier expression — the call-result
     * REEVALUATE_THUNK source (the function-typed-value child's ISSUE-0531
     * residue) — so the production lowering fails the module with the named
     * E6005 CONSTRUCT_UNLOWERED rule before any emission. The forward
     * binding-reference adapter source this fixture previously named is
     * production-covered now: a module-level declaration's execution
     * binding registers at the hoist, so the adapter's single proved
     * {@code BINDING_LOAD} resolves a statically fixed source identity.
     */
    private static final String ADAPTER_SOURCE_APP_SOURCE = """
        export function main(): null {
          let f: (a: int, b: int) => int = make();
          f(1, 2)
          return null
        }

        function make(): (a: int) => int {
          return function(a: int): int { return a; }
        }
        """;

    private static Fixture adapterSourceFixture() throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/app.deal", ADAPTER_SOURCE_APP_SOURCE)), Map.of());
    }

    private static Fixture nestedDeclarationFixture() throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/app.deal", NESTED_DECLARATION_APP_SOURCE)), Map.of());
    }

    private static Fixture asyncFixture(String libSource, String appSource)
            throws Exception {
        return compileProject(new LinkedHashMap<>(Map.of(
                "src/lib.deal", libSource,
                "src/app.deal", appSource)), Map.of());
    }

    private static Fixture hostFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/cfg.d.deal", HOST_DECLARATION);
        sources.put("src/app.deal", HOST_APP_SOURCE);
        return compileProject(sources,
            Map.of(HOST_CFG_SPECIFIER, "src/cfg.d.deal"));
    }

    private static Fixture externCFixture() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("src/native.d.deal", EXTERN_C_DECLARATION);
        sources.put("src/app.deal", EXTERN_C_APP_SOURCE);
        return compileProject(sources,
            Map.of(EXTERN_C_SPECIFIER, "src/native.d.deal"));
    }

    /** The emitted declared-map literal of one host import (declaration order). */
    private static String declaredMapLiteral(HostDeclarationSurface surface,
            ModuleId module) {
        HostDeclarationSurface.DeclarationFacts facts = surface.require(module);
        StringBuilder map = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, deal.types.Type> export : facts.exports().entrySet()) {
            if (!first) {
                map.append(", ");
            }
            first = false;
            map.append("[\"").append(export.getKey()).append("\"] = \"")
                .append(deal.semantic.DescriptorService.describe(export.getValue())
                    .canonicalSpecText())
                .append("\"");
        }
        return map.append("}").toString();
    }

    // =========================================================================
    // The unit driver
    // =========================================================================

    /** The one project lowering of the fixture over the production inputs. */
    private static SemanticLowerer.ProjectLoweringResult lower(Fixture fixture)
            throws Exception {
        return SemanticLowerer.lowerProject(productionInvocation(),
            fixture.checkedProject(), fixture.index(), fixture.manifests(),
            fixture.surface(), fixture.declarationIdentities(), fixture.externCModules(),
            BuiltinErrorDeclaration.synthesized(
                fixture.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of());
    }

    private static ProductionProjectEmission.Result emit(Fixture fixture,
            Backend backend, PublicationStager stager, boolean sourceMapExplicit)
            throws Exception {
        return ProductionProjectEmission.run(productionInvocation(),
            fixture.checkedProject(), fixture.index(), fixture.manifests(),
            fixture.surface(), fixture.declarationIdentities(),
            fixture.externCModules(),
            fixture.distributionHome().manifestDirectoryText(),
            BuiltinErrorDeclaration.synthesized(
                fixture.checkedProject().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                IntrinsicKind.BYTES_NEW),
            Set.of(), backend, sourceMapExplicit, fixture.distributionHome(), stager);
    }

    private record Emission(ProductionProjectEmission.Result result, String stderr) {
    }

    private static Emission emitCapturingStderr(Fixture fixture, Backend backend,
            PublicationStager stager, boolean sourceMapExplicit) throws Exception {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        ProductionProjectEmission.Result result;
        try {
            result = emit(fixture, backend, stager, sourceMapExplicit);
        } finally {
            System.setErr(originalErr);
        }
        return new Emission(result, buffer.toString(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // 1. The unit: one production source unit, one public static entry
    // =========================================================================

    private static void testUnitShapeAndConstants(Fixture fixture) throws Exception {
        System.out.println("-- the unit is one production source unit with one "
            + "public static entry --");

        Class<?> unit = ProductionProjectEmission.class;
        check(Modifier.isPublic(unit.getModifiers())
                && Modifier.isFinal(unit.getModifiers()),
            "ProductionProjectEmission is a public final production unit");
        checkEq("deal.module", unit.getPackageName(),
            "the unit sits in the production module package");

        List<String> publicStatic = new ArrayList<>();
        for (Method method : unit.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())) {
                publicStatic.add(method.getName());
            }
        }
        checkEq(List.of("run"), publicStatic,
            "the unit publishes exactly one public static entry");

        Method entry = unit.getMethod("run", CompilerInvocation.class,
            CheckedProjectInput.class, ProjectInterfaceIndex.class, List.class,
            HostDeclarationSurface.class, Map.class, Map.class, String.class,
            BuiltinErrorDeclaration.class, List.class, Set.class, Backend.class,
            boolean.class, DistributionHome.class, PublicationStager.class);
        checkEq(ProductionProjectEmission.Result.class, entry.getReturnType(),
            "the entry returns the run outcome");
        List<String> parameterTypes = new ArrayList<>();
        for (Class<?> parameter : entry.getParameterTypes()) {
            parameterTypes.add(parameter.getSimpleName());
        }
        checkEq(List.of("CompilerInvocation", "CheckedProjectInput",
                "ProjectInterfaceIndex", "List", "HostDeclarationSurface", "Map",
                "Map", "String", "BuiltinErrorDeclaration", "List", "Set", "Backend",
                "boolean", "DistributionHome", "PublicationStager"),
            parameterTypes,
            "the entry consumes the compile's declared inputs (the extern-C"
                + " generated modules and the manifest-directory text feeding"
                + " the Lua session's FFI emission input), the backend, the"
                + " effective source-map flag, the deployment-copy resolver, and the"
                + " staging surface");
        for (Class<?> parameter : entry.getParameterTypes()) {
            String name = parameter.getName();
            for (String forbidden : List.of("deal.ast.", "deal.parser.",
                    "Route", "MigrationPlanner", "CapabilityRegistry",
                    "HostModuleDeclarations", "CheckResult", "ProgramNode")) {
                check(!name.contains(forbidden),
                    "the entry takes no " + forbidden + " input: " + name);
            }
        }

        checkEq("SHARED_EMITTER_COVERAGE",
            ProductionProjectEmission.SHARED_EMITTER_COVERAGE,
            "the registered emitter-coverage rule id");
        checkEq("HOST_MODULE_IMPORT", ProductionProjectEmission.HOST_MODULE_IMPORT,
            "the stable extern-C declaration-import guard token");

        // The JS target is not a production arm.
        {
            PublicationStager stager =
                PublicationStager.forRoot(fixture.root().resolve("js-out"));
            try {
                boolean rejected = false;
                try {
                    emit(fixture, Backend.JS, stager, false);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                check(rejected, "the JS target is rejected by the production unit");
                check(stager.stagedSet().relativePaths().isEmpty(),
                    "the rejected JS run stages nothing");
            } finally {
                stager.discard();
            }
        }
    }

    // =========================================================================
    // 2. One lowering, one emission, one staged project artifact per target
    // =========================================================================

    private record UnmappedLua(String source, String stderr, Set<String> paths) {
    }

    private static UnmappedLua testOneArtifactPerTarget(Fixture fixture) throws Exception {
        System.out.println("-- one staged project artifact per target, the LuaJIT "
            + "deployment copies, no sidecar, byte-identical repeats --");
        Path luaOut = fixture.root().resolve("lua-out");
        Path jvmOut = fixture.root().resolve("jvm-out");
        Path luaOutRepeat = fixture.root().resolve("lua-out-repeat");
        UnmappedLua unmapped;
        {
            // LuaJIT: the chunk named for the entry module plus the unchanged
            // runtime/stdlib deployment copies.
            PublicationStager luaStager = PublicationStager.forRoot(luaOut);
            ProductionProjectEmission.Result luaResult;
            String firstChunk;
            try {
                Emission emission = emitCapturingStderr(fixture, Backend.LUAJIT,
                    luaStager, false);
                luaResult = emission.result();
                check(luaResult.emitted(),
                    "the LuaJIT production run emits: " + luaResult.diagnostics());
                checkEq("app.lua", luaResult.artifactRelativePath(),
                    "the LuaJIT artifact is the entry module's chunk");
                check(luaStager.stagedSet().artifact("app.lua").isPresent(),
                    "exactly one staged artifact at the pinned entry path");
                check(luaStager.stagedSet().artifact("lib.lua").isEmpty(),
                    "no per-module sibling artifact stages");
                check(luaStager.stagedSet().artifact("deal/runtime.lua").isPresent(),
                    "the unchanged LuaJIT runtime deployment copy stages");
                check(luaStager.stagedSet().artifact("std/console.lua").isPresent(),
                    "the unchanged stdlib deployment copies stage");
                for (String path : luaStager.stagedSet().relativePaths()) {
                    check(!path.endsWith(".deal.map.json"),
                        "no source-map sidecar stages: " + path);
                }
                checkEq(luaStager.stagedSet().size(),
                    1 + luaStager.stagedSet().relativePaths().stream()
                        .filter(path -> path.startsWith("std/")
                            || path.equals("deal/runtime.lua")).toList().size(),
                    "the LuaJIT staged set is the project artifact plus the "
                        + "deployment copies");
                unmapped = new UnmappedLua(new String(luaStager.stagedSet()
                    .artifact("app.lua").orElseThrow().content(),
                    StandardCharsets.UTF_8), emission.stderr(),
                    Set.copyOf(luaStager.stagedSet().relativePaths()));
                firstChunk = unmapped.source();
            } finally {
                luaStager.discard();
            }

            // JVM: one class source, no deployment copy and no sidecar.
            PublicationStager jvmStager = PublicationStager.forRoot(jvmOut);
            ProductionProjectEmission.Result jvmResult;
            try {
                jvmResult = emit(fixture, Backend.JVM, jvmStager, false);
                check(jvmResult.emitted(),
                    "the JVM production run emits: " + jvmResult.diagnostics());
                checkEq("App.java", jvmResult.artifactRelativePath(),
                    "the JVM artifact is the entry class named by classNameFor");
                checkEq(Set.of("App.java"), jvmStager.stagedSet().relativePaths(),
                    "exactly one staged artifact and nothing else on the JVM");
                jvmStager.publish();
            } finally {
                jvmStager.discard();
            }

            // Byte-identical repeated staging.
            PublicationStager repeatStager =
                PublicationStager.forRoot(luaOutRepeat);
            try {
                ProductionProjectEmission.Result repeat =
                    emit(fixture, Backend.LUAJIT, repeatStager, false);
                check(repeat.emitted(),
                    "the repeated LuaJIT run emits: " + repeat.diagnostics());
                String secondChunk = new String(repeatStager.stagedSet()
                    .artifact("app.lua").orElseThrow().content(),
                    StandardCharsets.UTF_8);
                checkEq(firstChunk, secondChunk,
                    "repeated compiles are byte-identical");
            } finally {
                repeatStager.discard();
            }
        }
        return unmapped;
    }

    private static void testOneMainProbe() throws Exception {
        System.out.println("-- the staged chunk delegates the entry main exactly "
            + "once --");
        Fixture fixture = twoModuleFixture(PROBE_APP_SOURCE);
        Path out = fixture.root().resolve("out-arm");
        try {
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result result =
                    emit(fixture, Backend.LUAJIT, stager, false);
                check(result.emitted(),
                    "the probe fixture emits: " + result.diagnostics());
                stager.publish();
            } finally {
                stager.discard();
            }
            check(Files.isRegularFile(out.resolve("app.lua")),
                "the published chunk exists at the pinned path");
            ProcessOutcome run = runProcess(List.of("luajit", "app.lua"), out);
            checkEq(0, run.exitCode(), "the published chunk exits 0: " + run.output());
            checkEq("PROBE|MAIN-ONCE\n", run.stdout(),
                "the entry main ran exactly once");
            checkEq("", run.stderr(),
                "the production chunk publishes no trace protocol on stderr");
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    // =========================================================================
    // 3. The real toolchains
    // =========================================================================

    private static void testJvmToolchain(Fixture fixture) throws Exception {
        System.out.println("-- the staged JVM artifact compiles with javac --release "
            + "25 -proc:none and runs under java --");
        Path out = fixture.root().resolve("jvm-out");
        Path classes = fixture.root().resolve("classes");
        {
            Path source = out.resolve("App.java");
            check(Files.isRegularFile(source),
                "the published JVM artifact exists at the pinned path");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", classes.toString(),
                source.toAbsolutePath().toString()), fixture.root());
            checkEq(0, javacRun.exitCode(),
                "the published JVM artifact compiles: " + javacRun.output());
            if (javacRun.exitCode() != 0) {
                return;
            }
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                classes + java.io.File.pathSeparator + classpath, "App"),
                fixture.root());
            checkEq(0, run.exitCode(), "the published JVM artifact runs: "
                + run.output());
            checkEq("", run.stdout(),
                "the successful JVM production run prints nothing on stdout");
            check(!run.stderr().contains("R|"),
                "the executed JVM artifact emits no trace line");
        }
    }

    // =========================================================================
    // 4. Atomic failure: a failing lowering; the realized cross-module sync
    //    call emits and stages
    // =========================================================================

    private static void testAtomicFailures(Fixture failing) throws Exception {
        System.out.println("-- a failing lowering stages nothing and preserves the "
            + "previous artifact set; the cross-module sync call emits and "
            + "stages --");

        Fixture bytes = bytesFixture();
        Path bytesOut = bytes.root().resolve("out-arm");
        try {
            PublicationStager stager = PublicationStager.forRoot(bytesOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(bytes, Backend.LUAJIT, stager, false);
                check(result.emitted(), "the bytes-bearing run emits: "
                    + result.diagnostics());
                check(result.diagnostics().isEmpty(),
                    "the bytes allocation carries no diagnostic: "
                        + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the bytes-bearing run stages the entry module's chunk");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the bytes-bearing run stages its one project artifact");
            } finally {
                stager.discard();
            }
        } finally {
            deleteRecursively(bytes.root());
        }

        Fixture nested = nestedDeclarationFixture();
        Path nestedOut = nested.root().resolve("out-arm");
        try {
            PublicationStager stager = PublicationStager.forRoot(nestedOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(nested, Backend.LUAJIT, stager, false);
                check(result.emitted(), "the nested-declaration run emits: "
                    + result.diagnostics());
                check(result.diagnostics().isEmpty(),
                    "the nested declaration carries no diagnostic: "
                        + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the nested-declaration run stages the entry module's chunk");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the nested-declaration run stages its one project artifact");
            } finally {
                stager.discard();
            }
        } finally {
            deleteRecursively(nested.root());
        }

        Path out = failing.root().resolve("out-arm");
        {
            writeFileIn(out, "app.lua", "-- previous artifact\n");
            writeFileIn(out, "lib.lua", "-- previous sibling\n");
            writeFileIn(out, "deal/runtime.lua", "-- previous runtime\n");
            Map<String, String> before = snapshotTree(out);
            PublicationStager stager = PublicationStager.forRoot(out);
            ProductionProjectEmission.Result result;
            try {
                result = emit(failing, Backend.LUAJIT, stager, false);
            } finally {
                stager.discard();
            }
            check(!result.emitted(), "the fail-closed run emits nothing");
            check(result.firstDiagnostic() != null
                    && "E6005".equals(result.firstDiagnostic().code()),
                "the failing lowering merges the first E6005: "
                    + result.diagnostics());
            check(result.firstDiagnostic() != null
                    && result.firstDiagnostic().message().contains("CONSTRUCT_UNLOWERED"),
                "the adapter-source construct is the named fail-closed rule: "
                    + result.diagnostics());
            checkEq(before, snapshotTree(out),
                "the failing lowering leaves the previous artifact set byte-identical");
        }

        Fixture sync = twoModuleFixture(SYNC_CALL_APP_SOURCE);
        Path syncOut = sync.root().resolve("out-arm");
        try {
            writeFileIn(syncOut, "app.lua", "-- previous artifact\n");
            writeFileIn(syncOut, "deal/runtime.lua", "-- previous runtime\n");
            PublicationStager stager = PublicationStager.forRoot(syncOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(sync, Backend.LUAJIT, stager, false);
                check(result.emitted(),
                    "the cross-module sync call emits: " + result.diagnostics());
                check(result.diagnostics().isEmpty(),
                    "the emitted cross-module sync call carries no diagnostic: "
                        + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the cross-module sync call stages the entry module's chunk");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the cross-module sync call stages its one project artifact");
                String chunk = new String(stager.stagedSet().artifact("app.lua")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
                check(chunk.contains("__module = \"lib\"")
                        && chunk.contains("__modStack[#__modStack + 1] = __module")
                        && chunk.contains("pcall(__factories.F")
                        && !chunk.contains("SharedM"),
                    "the staged chunk runs the callee unit's entry under the "
                        + "callee module context and references no per-module "
                        + "artifact");
                check(chunk.contains("__svStack"),
                    "the staged chunk preserves the callee body's private "
                        + "state across the invocation");
            } finally {
                stager.discard();
            }
        } finally {
            deleteRecursively(sync.root());
        }
    }

    // =========================================================================
    // 5. The pre-emission closure guard
    // =========================================================================

    private static void testHostImportGuard() throws Exception {
        System.out.println("-- the HOST-declaration import emits its declared-map "
            + "load and stages; the extern-C declaration import emits its "
            + "load_ffi prelude --");

        // The HOST-declaration-kind import: realized by the host load of
        // the module init walk (H1), so the production run stages its one
        // project artifact and no guard fires.
        Fixture fixture = hostFixture();
        Path out = fixture.root().resolve("out-arm");
        try {
            PublicationStager stager = PublicationStager.forRoot(out);
            ProductionProjectEmission.Result result;
            String chunk;
            try {
                result = emit(fixture, Backend.LUAJIT, stager, false);
                check(result.emitted(),
                    "the HOST-declaration import emits: " + result.diagnostics());
                check(result.diagnostics().isEmpty(),
                    "the HOST-declaration import carries no diagnostic: "
                        + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the HOST-declaration import stages the entry module's chunk");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the HOST-declaration import stages its one project artifact");
                chunk = new String(stager.stagedSet().artifact("app.lua")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
            } finally {
                stager.discard();
            }
            HostDeclarationSurface.DeclarationFacts facts =
                fixture.surface().require(HOST_CFG);
            checkEq(HostDeclarationSurface.DeclarationKind.HOST, facts.kind(),
                "the fixture's declaration module is HOST-kind");
            String loadPrefix = "__exportSurfaces[\"" + HOST_CFG.path()
                + "\"] = __exportSurfaces[\"" + HOST_CFG.path()
                + "\"] or __rt.load_host(\"" + HOST_CFG_SPECIFIER + "\", ";
            check(chunk.contains(loadPrefix
                    + declaredMapLiteral(fixture.surface(), HOST_CFG) + ", "),
                "the emitted artifact carries the load of " + HOST_CFG_SPECIFIER
                    + " with the declared map in declaration order");
            checkEq(1, countOccurrences(chunk,
                    "__rt.load_host(\"" + HOST_CFG_SPECIFIER + "\""),
                "the host module loads exactly once for the one import op");
            check(chunk.indexOf("__dealMain = function()")
                    < chunk.indexOf("__rt.load_host(\"" + HOST_CFG_SPECIFIER),
                "the load runs inline in the module init walk");
            check(!chunk.contains(ProductionProjectEmission.HOST_MODULE_IMPORT),
                "the emitted chunk carries no guard token");
        } finally {
            deleteRecursively(fixture.root());
        }

        Fixture externC = externCFixture();
        Path externCOut = externC.root().resolve("out-arm");
        try {
            checkEq(HostDeclarationSurface.DeclarationKind.EXTERN_C,
                externC.surface().require(NATIVE_MATH).kind(),
                "the probe's declaration module is EXTERN_C-kind");
            PublicationStager stager = PublicationStager.forRoot(externCOut);
            ProductionProjectEmission.Result result;
            String chunk;
            try {
                result = emit(externC, Backend.LUAJIT, stager, false);
                check(result.emitted(),
                    "the extern-C declaration import emits: "
                        + result.diagnostics());
                check(result.diagnostics().isEmpty(),
                    "the extern-C declaration import carries no guard "
                        + "diagnostic: " + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the extern-C declaration import stages the entry "
                        + "module's chunk");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the extern-C declaration import stages its one project "
                        + "artifact");
                chunk = new String(stager.stagedSet().artifact("app.lua")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
            } finally {
                stager.discard();
            }
            check(chunk.contains("__exportSurfaces[\"" + NATIVE_MATH.path()
                    + "\"] = __exportSurfaces[\"" + NATIVE_MATH.path()
                    + "\"] or __rt.load_ffi(\"ffi:@$external/"
                    + EXTERN_C_SPECIFIER + "\", "),
                "the emitted artifact carries the load_ffi prelude with the "
                    + "metadata-provided module key: " + chunk);
            check(chunk.contains(", \""
                    + externC.root().resolve("src/app.deal").toAbsolutePath()
                    + "\", 1, 1)"),
                "the load carries the import statement's span triplet: "
                    + chunk);
            check(!chunk.contains(ProductionProjectEmission.HOST_MODULE_IMPORT),
                "the emitted chunk carries no guard token");
            check(!chunk.contains("ffi.C") && !chunk.contains("cdef("),
                "the emitted chunk carries no ffi.C/cdef text");
        } finally {
            deleteRecursively(externC.root());
        }
    }

    /**
     * The preserved emission origin (ISSUE-0750): an extern-C declaration
     * whose carried forward bindings name a provider module the artifact
     * does not publish fails the emission closed with one E6005
     * {@code SHARED_EMITTER_COVERAGE} {@code FFI_PROVIDER_GAP} whose range
     * is the extern-C import statement's own SOURCE span — the identifiable
     * offending origin of the emission failure, never the empty-file
     * synthetic anchor — and stages nothing.
     */
    private static void testFfiProviderGapOrigin() throws Exception {
        System.out.println("-- the emission provider gap carries the "
            + "extern-C import statement's own origin --");

        Fixture externC = externCFixture();
        try {
            FfiGeneratedModule original =
                externC.externCModules().get(NATIVE_MATH);
            check(original != null,
                "the extern-C fixture carries its generated module");
            if (original == null) {
                return;
            }
            FfiForwardBindings bindings = original.bindings();
            FfiImportedFunctionReference gap = new FfiImportedFunctionReference(
                "gap", "gapExport", "gap.provider", "() -> int",
                "gap-digest", bindings.importedFunctions().size(),
                DiagnosticRange.synthetic("gap.provider.d.deal"));
            FfiForwardBindings doctoredBindings = new FfiForwardBindings(
                bindings.moduleKey(), bindings.state(), bindings.cells(),
                java.util.stream.Stream.concat(
                        bindings.importedFunctions().stream(),
                        java.util.stream.Stream.of(gap)).toList(),
                bindings.importedClassPlans());
            FfiGeneratedModule doctored = new FfiGeneratedModule(
                original.modulePath(), original.descriptor(),
                original.cdefBundle(), original.plans(), doctoredBindings);
            Map<ModuleId, FfiGeneratedModule> doctoredModules =
                new LinkedHashMap<>(externC.externCModules());
            doctoredModules.put(NATIVE_MATH, doctored);
            Fixture doctoredFixture = new Fixture(externC.root(),
                externC.checkedProject(), externC.index(), externC.manifests(),
                externC.surface(), doctoredModules,
                externC.declarationIdentities(), externC.distributionHome());

            Path out = externC.root().resolve("out-provider-gap");
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                ProductionProjectEmission.Result result =
                    emit(doctoredFixture, Backend.LUAJIT, stager, false);
                check(!result.emitted(),
                    "the provider gap fails the emission closed");
                checkEq(1, result.diagnostics().size(),
                    "exactly one E6005 diagnostic: " + result.diagnostics());
                if (result.diagnostics().size() == 1) {
                    CompilerDiagnostic diagnostic = result.firstDiagnostic();
                    checkEq("E6005", diagnostic.code(),
                        "the provider gap is E6005");
                    check(diagnostic.message().contains(
                            ProductionProjectEmission.SHARED_EMITTER_COVERAGE)
                            && diagnostic.message().contains(
                                ProductionProjectEmission.FFI_PROVIDER_GAP)
                            && diagnostic.message().contains(
                                "'" + EXTERN_C_SPECIFIER + "'")
                            && diagnostic.message().contains(
                                "gap.provider"),
                        "the E6005 message is the unchanged provider-gap text: "
                            + diagnostic.message());
                    check(diagnostic.range().origin() == RangeOrigin.SOURCE,
                        "the E6005 range is SOURCE, got "
                            + diagnostic.range().origin());
                    String entry = externC.root().resolve("src/app.deal")
                        .toAbsolutePath().normalize().toString();
                    checkEq(entry, diagnostic.range().file(),
                        "the E6005 range names the consuming entry file");
                    checkEq(1, diagnostic.range().startLine(),
                        "the E6005 range starts at the import statement's line");
                    checkEq(1, diagnostic.range().startColumn(),
                        "the E6005 range starts at the import statement's "
                            + "column");
                    // The frontend's import-declaration span runs to the
                    // token after the statement (the import carries no
                    // trailing semicolon); the range preserves that
                    // recorded span exactly.
                    checkEq(3, diagnostic.range().endLine(),
                        "the E6005 range ends at the recorded import span end");
                    checkEq(7, diagnostic.range().endColumn(),
                        "the E6005 range ends at the recorded import span end "
                            + "column");
                    checkEq(45, diagnostic.range().scalarLength(),
                        "the E6005 range covers the recorded import span, got "
                            + diagnostic.range());
                }
                check(stager.stagedSet().relativePaths().isEmpty(),
                    "the provider gap stages nothing: "
                        + stager.stagedSet().relativePaths());
            } finally {
                stager.discard();
            }
        } finally {
            deleteRecursively(externC.root());
        }
    }

    /**
     * The rejected-op emission origins (ISSUE-0750 D4): both shared
     * production emitters refuse an explicitly rejected op and a detached
     * body with the {@link SharedEmissionFailure} signal carrying that
     * op's own copied origin — the production arm then projects exactly
     * that range through the unchanged SHARED_EMITTER_COVERAGE
     * classification. A detached default body with no membership row and a
     * CLASS_DEFAULT op reached by the block walk are the explicit op and
     * detached-body rejection boundaries; the ordinary originless
     * IllegalStateException surface stays unchanged.
     */
    private static void testRejectedOpEmissionOrigins() throws Exception {
        System.out.println("-- rejected-op emission origins: both shared targets carry "
            + "the rejected op's own origin at the op and detached-body boundaries --");

        Fixture fixture = compileProject(
            Map.of("src/app.deal", CLASS_DEFAULT_APP_SOURCE), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult lowering = lower(fixture);
            check(!lowering.hasErrors(),
                "the exported-class fixture lowers: " + lowering.diagnostics());
            if (lowering.hasErrors()) {
                return;
            }
            ExecutableLoweredProject project = lowering.project();
            LoweredModuleUnit entry = project.modules().get(project.entryModule());
            SemanticOp classDefault = null;
            for (SemanticOp op : entry.ops()) {
                if (op.kind() == SemanticOpKind.CLASS_DEFAULT) {
                    classDefault = op;
                    break;
                }
            }
            check(classDefault != null,
                "the lowered entry module carries the CLASS_DEFAULT op");
            if (classDefault == null) {
                return;
            }
            DiagnosticRange expected = classDefault.origin().span().range();
            check(expected.origin() == RangeOrigin.SOURCE
                    && expected.scalarLength() > 0,
                "the rejected op's own origin is a real SOURCE span: " + expected);
            StructuredBodyTable table = lowering.tables().get(entry.moduleId());
            BlockId initBlock = entry.moduleInit().initBlock();

            // (a) The explicit op rejection: the CLASS_DEFAULT op is reached
            // by the block walk (a doctored membership row) and the emitOp
            // dispatch refuses it.
            Map<BlockId, List<OpId>> walkedRows = new LinkedHashMap<>();
            for (Map.Entry<BlockId, List<OpId>> row : table.blockOps().entrySet()) {
                List<OpId> ops = new ArrayList<>(row.getValue());
                if (row.getKey().equals(initBlock)) {
                    ops.add(classDefault.opId());
                }
                walkedRows.put(row.getKey(), ops);
            }
            StructuredBodyTable walked =
                new StructuredBodyTable(walkedRows, table.opBlocks());

            // (b) The detached-body rejection: the class-default block's
            // membership row is absent, so the detached default function
            // cannot be emitted.
            BlockId defaultBlock =
                ((KindPayload.ClassDefaultPayload) classDefault.payload())
                    .defaultBlock();
            Map<BlockId, List<OpId>> detachedRows =
                new LinkedHashMap<>(table.blockOps());
            detachedRows.remove(defaultBlock);
            StructuredBodyTable detached =
                new StructuredBodyTable(detachedRows, table.opBlocks());

            for (String target : List.of("LuaJIT", "JVM")) {
                SharedEmissionFailure opRejection = emitFailure(target, project,
                    Map.of(entry.moduleId(), walked), lowering, fixture);
                check(opRejection != null,
                    target + ": the walked CLASS_DEFAULT op is rejected");
                if (opRejection != null) {
                    checkEq(expected, opRejection.diagnosticRange(),
                        target + ": the op rejection carries the rejected op's own "
                            + "exact range");
                    check(opRejection.getMessage().contains(
                            "a CLASS_DEFAULT executes only under"),
                        target + ": the op rejection message is the unchanged text: "
                            + opRejection.getMessage());
                }

                SharedEmissionFailure detachedRejection = emitFailure(target, project,
                    Map.of(entry.moduleId(), detached), lowering, fixture);
                check(detachedRejection != null,
                    target + ": the missing default-block membership rejects the "
                        + "detached body");
                if (detachedRejection != null) {
                    checkEq(expected, detachedRejection.diagnosticRange(),
                        target + ": the detached-body rejection carries the "
                            + "CLASS_DEFAULT op's own exact range");
                    check(detachedRejection.getMessage().contains(
                            "has no membership row"),
                        target + ": the detached-body message is the unchanged text: "
                            + detachedRejection.getMessage());
                }
            }

            // The ordinary genuinely originless emitter exception surface is
            // unchanged: it implements no origin carrier.
            IllegalStateException originless =
                new IllegalStateException("an ordinary emitter refusal");
            check(!(originless instanceof deal.semantic.ir.DiagnosticOriginCarrier),
                "an ordinary IllegalStateException carries no origin (the canonical "
                    + "synthetic projection is unchanged)");
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /** The imported-class fixture: a SHARED_FACTORY CLASS_NEW and class field reads. */
    private static final String IMPORTED_CLASS_LIB_SOURCE = """
        export class Point {
          x: int = 0;
          y: int = 0;
        }
        """;

    private static final String IMPORTED_CLASS_APP_SOURCE = """
        import * as Lib from "./lib"

        export function main(): null {
          let p: Lib.Point = { x: 1, y: 2 };
          let s: int = p.x + p.y;
          return null;
        }
        """;

    /**
     * The remaining identified shared emission guards keep the explicit
     * offending operation's own exact range (ISSUE-0750 D4): the
     * CLASS_NEW factory lookup, the missing field boundary child, the
     * unresolved async external-entry link, the duplicate async entry, and
     * the JVM declaration-kind mismatch. Each seed is the closed fact no
     * checker-valid program produces (a direct corrupted-IR boundary),
     * driven through both shared targets where the guard is shared.
     */
    private static void testIdentifiedSharedGuardOrigins() throws Exception {
        System.out.println("-- the remaining identified shared emission guards "
            + "carry the offending op's own exact range --");

        // (a)/(b) The imported-class fixture: the SHARED_FACTORY CLASS_NEW
        // whose factory reference resolves in no registry, and the
        // FIELD_READ whose required boundary child is absent.
        Fixture importedClass = compileProject(new LinkedHashMap<>(Map.of(
                "src/lib.deal", IMPORTED_CLASS_LIB_SOURCE,
                "src/app.deal", IMPORTED_CLASS_APP_SOURCE)), Map.of());
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(importedClass);
            check(!result.hasErrors(),
                "the imported-class fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp sharedFactory = null;
                SemanticOp fieldRead = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.CLASS_NEW
                            && op.payload()
                                instanceof KindPayload.ClassNewPayload payload
                            && payload.defaultOwner() == DefaultOwner.SHARED_FACTORY) {
                        sharedFactory = op;
                    }
                    if (op.kind() == SemanticOpKind.FIELD_READ && fieldRead == null) {
                        fieldRead = op;
                    }
                }
                check(sharedFactory != null,
                    "the imported-class fixture lowers the SHARED_FACTORY CLASS_NEW");
                check(fieldRead != null,
                    "the imported-class fixture lowers the FIELD_READ");
                if (sharedFactory != null) {
                    KindPayload.ClassNewPayload payload =
                        (KindPayload.ClassNewPayload) sharedFactory.payload();
                    ClassFactoryId bogus = new ClassFactoryId(Long.MAX_VALUE);
                    KindPayload.ClassNewPayload replaced =
                        new KindPayload.ClassNewPayload(payload.classId(),
                            payload.layout(), payload.providedFields(),
                            DefaultOwner.SHARED_FACTORY, payload.classDefaultOpIds(),
                            bogus, payload.fieldBoundaries());
                    assertSharedGuardOrigin(
                        "the unregistered class-factory reference", sharedFactory,
                        "CLASS_NEW " + sharedFactory.opId() + " classFactoryRef "
                            + bogus + " resolves in no module's"
                            + " ClassFactoryRegistry in the closure (producer defect)",
                        replaceUnitOp(result.project(), entry, sharedFactory, replaced),
                        result, importedClass);
                }
                if (fieldRead != null) {
                    assertFieldBoundaryGuardOrigin(fieldRead, result, importedClass);
                }
            }
        } finally {
            deleteRecursively(importedClass.root());
        }

        // (c) The cross-module async fixture: the ASYNC_START(EXTERNAL)
        // whose recorded link names a module outside the closure. The
        // async external-entry guard identifies the start op and carries
        // its own exact range.
        Fixture crossAsync = asyncFixture(ASYNC_LIB_SOURCE, CROSS_ASYNC_APP_SOURCE);
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(crossAsync);
            check(!result.hasErrors(),
                "the cross-module async fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp asyncStart = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.ASYNC_START
                            && op.payload()
                                instanceof KindPayload.AsyncStartPayload payload
                            && payload.externalAsyncLink() != null) {
                        asyncStart = op;
                    }
                }
                check(asyncStart != null,
                    "the cross-module async fixture lowers the ASYNC_START(EXTERNAL)");
                if (asyncStart != null) {
                    KindPayload.AsyncStartPayload payload =
                        (KindPayload.AsyncStartPayload) asyncStart.payload();
                    ExternalAsyncLink bogus = new ExternalAsyncLink(
                        new ModuleId("outside"),
                        payload.externalAsyncLink().exportName(),
                        payload.externalAsyncLink().calleeTokenId());
                    KindPayload.AsyncStartPayload replaced =
                        new KindPayload.AsyncStartPayload(payload.callee(),
                            payload.source(), payload.parameterBoundaryMode(),
                            payload.parameterBoundaryOpIds(),
                            payload.completionDescriptor(),
                            payload.returnBoundaryOpId(),
                            payload.hostOperationLabel(), bogus);
                    assertSharedGuardOrigin(
                        "the async link naming a module outside the closure",
                        asyncStart,
                        "ASYNC_START " + asyncStart.opId()
                            + " resolves the external callee module outside"
                            + " outside the session's closure (producer defect)",
                        replaceUnitOp(result.project(), entry, asyncStart, replaced),
                        result, crossAsync);
                }
            }
        } finally {
            deleteRecursively(crossAsync.root());
        }

        // (d) The entry module's duplicated async EXTERNAL_ENTRY: the
        // second recorded entry (a distinct origin) is the operation whose
        // processing detects the collision, so the guard carries its own
        // exact range on both targets.
        Fixture duplicatedAsync =
            asyncFixture(ASYNC_LIB_SOURCE, CROSS_ASYNC_APP_SOURCE);
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(duplicatedAsync);
            check(!result.hasErrors(),
                "the duplicate-async-entry fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp asyncEntry = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                            && op.payload()
                                instanceof KindPayload.ExternalEntryPayload payload
                            && payload.async()) {
                        asyncEntry = op;
                    }
                }
                check(asyncEntry != null,
                    "the async fixture's entry module records the async entry");
                if (asyncEntry != null) {
                    String exportName = ((KindPayload.ExternalEntryPayload)
                        asyncEntry.payload()).exportName();
                    SourceOrigin duplicateOrigin = new SourceOrigin(
                        "duplicate.deal",
                        new SourceSpan("duplicate.deal", 2, 3, 2, 8, 30, 35),
                        SourceOriginKind.USER, new AnchorId(777), null);
                    SemanticOp duplicate = new SemanticOp(
                        new OpId(entry.moduleId(), 900001L), asyncEntry.kind(),
                        duplicateOrigin, new ValueId(900001L), asyncEntry.resultType(),
                        asyncEntry.operands(), asyncEntry.operandTypes(),
                        asyncEntry.payload(), asyncEntry.failurePolicy(),
                        asyncEntry.contract());
                    List<SemanticOp> ops = new ArrayList<>(entry.ops());
                    ops.add(duplicate);
                    Map<ModuleId, LoweredModuleUnit> modules =
                        new LinkedHashMap<>(result.project().modules());
                    modules.put(entry.moduleId(), withUnitOps(entry, ops));
                    ExecutableLoweredProject duplicated =
                        new ExecutableLoweredProject(
                            result.project().semanticProfile(),
                            result.project().interfaceIndex(), modules,
                            result.project().entryModule());
                    assertSharedGuardOrigin("the duplicated async entry", duplicate,
                        "the exported declaration '" + entry.moduleId().path()
                            + "#" + exportName + " records two async"
                            + " EXTERNAL_ENTRY ops (producer defect)",
                        duplicated, result, duplicatedAsync);
                }
            }
        } finally {
            deleteRecursively(duplicatedAsync.root());
        }

        // (e) The JVM declaration-kind mismatch: the entry's HOST import is
        // described by a declaration surface of the extern-C kind.
        Fixture host = hostFixture();
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(host);
            check(!result.hasErrors(),
                "the host-import fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp hostImport = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(HOST_CFG)) {
                        hostImport = op;
                    }
                }
                check(hostImport != null,
                    "the host fixture lowers the HOST MODULE_IMPORT");
                if (hostImport != null) {
                    HostDeclarationSurface.DeclarationFacts facts =
                        host.surface().require(HOST_CFG);
                    Map<ModuleId, HostDeclarationSurface.DeclarationFacts> modules =
                        new LinkedHashMap<>(host.surface().modules());
                    modules.put(HOST_CFG,
                        new HostDeclarationSurface.DeclarationFacts(HOST_CFG,
                            HostDeclarationSurface.DeclarationKind.EXTERN_C,
                            facts.exports(), facts.classes()));
                    HostDeclarationSurface doctored =
                        new HostDeclarationSurface(modules);
                    DiagnosticRange expected = hostImport.origin().span().range();
                    check(expected.origin() == RangeOrigin.SOURCE
                            && expected.scalarLength() > 0,
                        "the HOST MODULE_IMPORT carries a real SOURCE span: "
                            + expected);
                    try {
                        JvmSemanticEmitter.emitProductionProject(result.project(),
                            result.tables(), result.registries(), "FixtureProbe",
                            doctored);
                        fail("the JVM emitter rejects the declaration-kind "
                            + "mismatch");
                    } catch (SharedEmissionFailure rejection) {
                        checkEq("the declaration module '" + HOST_CFG.path()
                                + "' of MODULE_IMPORT " + hostImport.opId()
                                + " is kind "
                                + HostDeclarationSurface.DeclarationKind.EXTERN_C
                                + ": the extern-C declaration load is the FFI"
                                + " child's (fail-closed remnant)",
                            rejection.getMessage(),
                            "the declaration-kind rejection message is the"
                                + " unchanged text");
                        checkEq(expected, rejection.diagnosticRange(),
                            "the declaration-kind rejection carries the HOST"
                                + " MODULE_IMPORT's own exact range");
                    } catch (IllegalStateException other) {
                        fail("the declaration-kind rejection is the"
                            + " source-bearing signal: " + other);
                    }
                }
            }
        } finally {
            deleteRecursively(host.root());
        }
    }

    @FunctionalInterface
    private interface EmitterDrive {
        void emit() throws Exception;
    }

    /**
     * The absent declaration-surface entry (ISSUE-0750 D4): both emitter
     * boundaries that resolve an import's declaration module hold the
     * identifying MODULE_IMPORT operation, so the fail-closed lookup throws
     * the source-bearing shared signal carrying that import's own exact
     * range — and the production arm's own projection merges it into the
     * unchanged {@code SHARED_EMITTER_COVERAGE} E6005 — instead of the
     * canonical empty-file synthetic anchor. A genuinely originless emitter
     * refusal keeps the canonical synthetic projection.
     */
    private static void testAbsentDeclarationSurfaceEntries() throws Exception {
        System.out.println("-- an absent declaration-surface entry carries the "
            + "identifying MODULE_IMPORT's own exact range on both targets --");

        Fixture host = hostFixture();
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(host);
            check(!result.hasErrors(),
                "the host-import fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp hostImport = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(HOST_CFG)) {
                        hostImport = op;
                    }
                }
                check(hostImport != null,
                    "the host fixture lowers the HOST MODULE_IMPORT");
                if (hostImport != null) {
                    DiagnosticRange expected = hostImport.origin().span().range();
                    check(expected.origin() == RangeOrigin.SOURCE
                            && expected.scalarLength() > 0,
                        "the HOST MODULE_IMPORT carries a real SOURCE span: "
                            + expected);
                    String expectedMessage =
                        "declaration module '" + HOST_CFG.path()
                            + "' has no declaration-surface entry: the surface covers"
                            + " every host and extern-C declaration module of the"
                            + " compilation (producer defect)";
                    HostDeclarationSurface empty = new HostDeclarationSurface(Map.of());

                    SharedEmissionFailure luaRejection = assertAbsentEntryRejection(
                        "LuaJIT", "the absent declaration-surface entry", expected,
                        expectedMessage, () ->
                            LuaSemanticEmitter.emitProductionProject(result.project(),
                                result.tables(), result.registries(), empty, null));
                    assertAbsentEntryRejection("JVM",
                        "the absent declaration-surface entry", expected,
                        expectedMessage, () ->
                            JvmSemanticEmitter.emitProductionProject(result.project(),
                                result.tables(), result.registries(), "FixtureProbe",
                                empty));
                    if (luaRejection != null) {
                        assertProjectedEmitterOrigin("the absent declaration-surface "
                            + "entry", expected, expectedMessage, result,
                            luaRejection);
                    }
                }
            }
        } finally {
            deleteRecursively(host.root());
        }

        // The FFI emission input's absent generated-module entry: the same
        // import boundary identifies the extern-C MODULE_IMPORT whose own
        // range must survive the fail-closed lookup.
        Fixture externC = externCFixture();
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(externC);
            check(!result.hasErrors(),
                "the extern-C fixture lowers: " + result.diagnostics());
            if (!result.hasErrors()) {
                LoweredModuleUnit entry = result.project()
                    .modules().get(result.project().entryModule());
                SemanticOp nativeImport = null;
                for (SemanticOp op : entry.ops()) {
                    if (op.kind() == SemanticOpKind.MODULE_IMPORT
                            && op.payload()
                                instanceof KindPayload.ModuleImportPayload payload
                            && payload.resolvedModule().equals(NATIVE_MATH)) {
                        nativeImport = op;
                    }
                }
                check(nativeImport != null,
                    "the extern-C fixture lowers the extern-C MODULE_IMPORT");
                if (nativeImport != null) {
                    DiagnosticRange expected = nativeImport.origin().span().range();
                    check(expected.origin() == RangeOrigin.SOURCE
                            && expected.scalarLength() > 0,
                        "the extern-C MODULE_IMPORT carries a real SOURCE span: "
                            + expected);
                    String expectedMessage =
                        "the extern-C declaration module '" + NATIVE_MATH.path()
                            + "' carries no generated-module entry in the FFI"
                            + " emission input: the input covers exactly the"
                            + " compile's validated extern-C metadata (a producer"
                            + " defect, never a silently omitted load)";
                    SharedEmissionFailure rejection = assertAbsentEntryRejection(
                        "LuaJIT", "the absent FFI generated-module entry", expected,
                        expectedMessage, () ->
                            LuaSemanticEmitter.emitProductionProject(result.project(),
                                result.tables(), result.registries(),
                                externC.surface(), new FfiEmissionInput(Map.of(),
                                    "manifests")));
                    if (rejection != null) {
                        assertProjectedEmitterOrigin("the absent FFI generated-module "
                            + "entry", expected, expectedMessage, result, rejection);
                    }
                }
            }
        } finally {
            deleteRecursively(externC.root());
        }
    }

    /**
     * One emitter drive of an absent lookup entry: the rejection must be the
     * source-bearing shared signal with the unchanged complete message and
     * the identifying import operation's own exact range.
     */
    private static SharedEmissionFailure assertAbsentEntryRejection(String target,
            String what, DiagnosticRange expected, String expectedMessage,
            EmitterDrive drive) throws Exception {
        try {
            drive.emit();
            fail(what + " [" + target + "]: the emitter rejects the absent entry");
            return null;
        } catch (SharedEmissionFailure rejection) {
            checkEq(expectedMessage, rejection.getMessage(),
                what + " [" + target + "]: the rejection message is the unchanged"
                    + " complete text");
            checkEq(expected, rejection.diagnosticRange(),
                what + " [" + target + "]: the rejection carries the identifying"
                    + " MODULE_IMPORT's own exact range");
            check(rejection.getCause() instanceof IllegalStateException
                    && expectedMessage.equals(rejection.getCause().getMessage()),
                what + " [" + target + "]: the failed lookup remains the rejection's"
                    + " own cause with the unchanged message: " + rejection.getCause());
            return rejection;
        } catch (IllegalStateException other) {
            fail(what + " [" + target + "]: the rejection is the source-bearing"
                + " shared emitter signal: " + other);
            return null;
        }
    }

    /**
     * The production arm's own projection of one source-bearing emitter
     * rejection (the real private {@code sharedEmitterCoverage} step of
     * {@link ProductionProjectEmission#run}, invoked so the regression
     * exercises the production projection instead of re-implementing it),
     * plus the genuinely originless control.
     */
    private static void assertProjectedEmitterOrigin(String what,
            DiagnosticRange expected, String expectedMessage,
            SemanticLowerer.ProjectLoweringResult lowering,
            SharedEmissionFailure rejection) throws Exception {
        String entryPath = lowering.project().entryModule().path();
        CompilerDiagnostic projected = projectEmitterRejection(entryPath, rejection);
        checkEq("E6005", projected.code(),
            what + ": the projected emitter failure is E6005");
        check(projected.message().contains(
                ProductionProjectEmission.SHARED_EMITTER_COVERAGE),
            what + ": the projected failure keeps the SHARED_EMITTER_COVERAGE"
                + " classification: " + projected.message());
        check(projected.message().contains(expectedMessage),
            what + ": the projected E6005 keeps the complete emitter message");
        checkEq(expected, projected.range(),
            what + ": the projected E6005 carries the identifying import's own"
                + " exact range");
        check(projected.range().origin() == RangeOrigin.SOURCE,
            what + ": the projected range is SOURCE, got "
                + projected.range().origin());
        check(projected.notes().isEmpty(),
            what + ": the well-formed carried origin adds no note: "
                + projected.notes());

        CompilerDiagnostic originless = projectEmitterRejection(entryPath,
            new IllegalStateException("an ordinary emitter refusal"));
        check(originless.range().isCanonicalSynthetic(),
            what + ": an ordinary emitter refusal keeps the canonical synthetic"
                + " projection, got " + originless.range());
        checkEq("", originless.range().file(),
            what + ": the originless projection borrows no source file");
    }

    /** The production arm's own projection of one emitter rejection. */
    private static CompilerDiagnostic projectEmitterRejection(String entryModulePath,
            IllegalStateException rejection) throws Exception {
        Method projection = ProductionProjectEmission.class.getDeclaredMethod(
            "sharedEmitterCoverage", String.class, IllegalStateException.class,
            CompilerInvocation.class);
        projection.setAccessible(true);
        return (CompilerDiagnostic) projection.invoke(null, entryModulePath,
            rejection, productionInvocation());
    }

    /**
     * The empty-catalog STDLIB import (ISSUE-0750 D4): the recorded
     * import entry keeps the identifying {@code MODULE_IMPORT} operation,
     * so the fail-closed catalog miss at both emitter boundaries throws
     * the source-bearing shared signal carrying that import's own exact
     * range — and the production arm's own projection merges it into the
     * unchanged {@code SHARED_EMITTER_COVERAGE} E6005 — instead of the
     * canonical empty-file synthetic anchor. The carried range is the
     * span of the import that recorded the failing catalog entry (never
     * another matching import's); a known-file offsetless import keeps
     * its supplied file, and a genuinely originless emitter refusal keeps
     * the canonical synthetic projection.
     */
    private static void testStdlibCatalogMissOrigin() throws Exception {
        System.out.println("-- the empty-catalog STDLIB import guard carries the "
            + "recorded MODULE_IMPORT's own exact range on both targets --");

        Fixture fixture = twoModuleFixture(TWO_STDLIB_APP_SOURCE);
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            check(!result.hasErrors(),
                "the two-stdlib fixture lowers: " + result.diagnostics());
            if (result.hasErrors()) {
                return;
            }
            LoweredModuleUnit entry = result.project()
                .modules().get(result.project().entryModule());
            SemanticOp consoleImport = null;
            SemanticOp stringImport = null;
            for (SemanticOp op : entry.ops()) {
                if (op.kind() != SemanticOpKind.MODULE_IMPORT
                        || !(op.payload()
                            instanceof KindPayload.ModuleImportPayload payload)
                        || payload.kind() != ModuleImportKind.STDLIB) {
                    continue;
                }
                if (payload.resolvedModule().path().equals("std.console")) {
                    consoleImport = op;
                } else if (payload.resolvedModule().path().equals("std.string")) {
                    stringImport = op;
                }
            }
            check(consoleImport != null && stringImport != null,
                "the fixture lowers both STDLIB MODULE_IMPORTs");
            if (consoleImport == null || stringImport == null) {
                return;
            }
            KindPayload.ModuleImportPayload payload =
                (KindPayload.ModuleImportPayload) stringImport.payload();
            ModuleId missing = new ModuleId("std/missing");
            KindPayload.ModuleImportPayload doctoredPayload =
                new KindPayload.ModuleImportPayload(payload.rawSpecifier(),
                    missing, payload.kind(), payload.aliasCells());
            ExecutableLoweredProject doctored = replaceUnitOp(result.project(),
                entry, stringImport, doctoredPayload);
            DiagnosticRange expected = stringImport.origin().span().range();
            DiagnosticRange first = consoleImport.origin().span().range();
            check(expected.origin() == RangeOrigin.SOURCE
                    && expected.scalarLength() > 0,
                "the doctored STDLIB MODULE_IMPORT carries a real SOURCE span: "
                    + expected);
            check(!expected.equals(first),
                "the two STDLIB imports carry distinct spans: " + expected
                    + " vs " + first);

            String expectedMessage = "the STDLIB import of module '"
                + missing.path() + "' resolves no closed catalog row (the catalog"
                + " is the resolution authority — a producer defect)";
            SharedEmissionFailure luaRejection =
                assertStdlibCatalogMissRejection("LuaJIT", expected,
                    expectedMessage, () ->
                        LuaSemanticEmitter.emitProductionProject(doctored,
                            result.tables(), result.registries(),
                            fixture.surface(), null));
            SharedEmissionFailure jvmRejection =
                assertStdlibCatalogMissRejection("JVM", expected, expectedMessage,
                    () -> JvmSemanticEmitter.emitProductionProject(doctored,
                        result.tables(), result.registries(), "FixtureProbe",
                        fixture.surface()));
            if (luaRejection != null) {
                assertProjectedEmitterOrigin("the empty-catalog STDLIB import",
                    expected, expectedMessage, result, luaRejection);
            }
            if (jvmRejection != null) {
                assertProjectedEmitterOrigin("the empty-catalog STDLIB import",
                    expected, expectedMessage, result, jvmRejection);
            }

            // The known-file offsetless import: the synthetic conversion
            // keeps the supplied file and the production projection does
            // too — never the canonical empty-file anchor.
            SourceOrigin offsetlessOrigin = new SourceOrigin("offsetless.deal",
                new SourceSpan("offsetless.deal", 3, 4, 3, 9),
                SourceOriginKind.USER, new AnchorId(4242), null);
            ExecutableLoweredProject offsetless = replaceUnitOp(result.project(),
                entry, stringImport, doctoredPayload, offsetlessOrigin);
            DiagnosticRange offsetlessExpected = offsetlessOrigin.span().range();
            check(offsetlessExpected.origin() == RangeOrigin.SYNTHETIC
                    && offsetlessExpected.file().equals("offsetless.deal")
                    && offsetlessExpected.scalarLength() == 0,
                "the offsetless import keeps its known file under the synthetic"
                    + " conversion: " + offsetlessExpected);
            SharedEmissionFailure offsetlessLua =
                assertStdlibCatalogMissRejection("LuaJIT", offsetlessExpected,
                    expectedMessage, () ->
                        LuaSemanticEmitter.emitProductionProject(offsetless,
                            result.tables(), result.registries(),
                            fixture.surface(), null));
            SharedEmissionFailure offsetlessJvm =
                assertStdlibCatalogMissRejection("JVM", offsetlessExpected,
                    expectedMessage, () ->
                        JvmSemanticEmitter.emitProductionProject(offsetless,
                            result.tables(), result.registries(), "FixtureProbe",
                            fixture.surface()));
            if (offsetlessLua != null) {
                assertProjectedOffsetlessImport(offsetlessExpected, result,
                    offsetlessLua);
            }
            if (offsetlessJvm != null) {
                assertProjectedOffsetlessImport(offsetlessExpected, result,
                    offsetlessJvm);
            }
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /**
     * ISSUE-0750: a declaration-owned descriptor failure through the real
     * {@code lowerProject} entry — the corrupted checked function
     * signature (the closed fact no checker-valid program produces) fails
     * the project lowering closed with no project, and the shared E6005
     * carries the function declaration's own exact range.
     */
    private static void testDeclarationSignatureOrigin() throws Exception {
        System.out.println("-- the corrupted declared function signature keeps "
            + "the declaration's own range through lowerProject --");

        Fixture fixture = compileProject(new LinkedHashMap<>(Map.of(
            "src/app.deal", DECLARATION_ORIGIN_APP_SOURCE)), Map.of());
        try {
            FunctionDeclaration target = null;
            SymbolTable table = null;
            for (CheckedModuleInput module : fixture.checkedProject().modules()) {
                for (StatementNode statement : module.ast().statements()) {
                    if (statement instanceof FunctionDeclaration function
                            && function.name().equals("f")) {
                        target = function;
                        table = module.checks().symbolTable();
                    }
                }
            }
            check(target != null && table != null,
                "the fixture carries the checked function declaration f");
            if (target == null || table == null) {
                return;
            }
            table.remove("f");
            table.define("f", new Symbol.FunctionSymbol("f",
                new Type.Func(List.of(), Type.Error.INSTANCE, false)));

            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            check(result.project() == null && !result.diagnostics().isEmpty()
                    && result.tables().isEmpty()
                    && result.registries().isEmpty(),
                "the corrupted signature fails the project lowering closed with "
                    + "no project, tables, or registries");
            if (result.diagnostics().isEmpty()) {
                return;
            }
            CompilerDiagnostic diagnostic = result.diagnostics().get(0);
            DiagnosticRange expected = target.span().range();
            check(expected.origin() == RangeOrigin.SOURCE
                    && expected.file().endsWith("src/app.deal")
                    && expected.scalarLength() > 0,
                "the function declaration carries a real SOURCE span in its own "
                    + "module file: " + expected);
            check("E6005".equals(diagnostic.code())
                    && diagnostic.message().contains(
                        ContainerPayloadDescriptors.DESCRIPTOR_UNREPRESENTABLE),
                "the corrupted signature converts to the unchanged E6005 "
                    + "DESCRIPTOR_UNREPRESENTABLE; got " + diagnostic.message());
            checkEq(expected, diagnostic.range(),
                "the failure carries exactly the function declaration's own "
                    + "converted range");
        } finally {
            deleteRecursively(fixture.root());
        }
    }

    /**
     * The production arm's own projection of one known-file offsetless
     * rejection: the synthetic shape keeps the supplied file and never
     * borrows the canonical empty one.
     */
    private static void assertProjectedOffsetlessImport(DiagnosticRange expected,
            SemanticLowerer.ProjectLoweringResult lowering,
            SharedEmissionFailure rejection) throws Exception {
        CompilerDiagnostic projected = projectEmitterRejection(
            lowering.project().entryModule().path(), rejection);
        checkEq("E6005", projected.code(),
            "the offsetless import projection is E6005");
        checkEq(expected, projected.range(),
            "the offsetless import projection keeps the known file's synthetic"
                + " range");
        checkEq(expected.file(), projected.range().file(),
            "the offsetless import projection never borrows the empty file");
        check(projected.range().origin() == RangeOrigin.SYNTHETIC,
            "the offsetless import projection stays SYNTHETIC, got "
                + projected.range().origin());
    }

    /**
     * One emitter drive of the empty-catalog STDLIB import: the rejection
     * must be the source-bearing shared signal with the unchanged complete
     * message and the identifying {@code MODULE_IMPORT}'s own exact
     * range.
     */
    private static SharedEmissionFailure assertStdlibCatalogMissRejection(
            String target, DiagnosticRange expected, String expectedMessage,
            EmitterDrive drive) throws Exception {
        try {
            drive.emit();
            fail("the empty-catalog STDLIB import [" + target
                + "]: the emitter rejects");
            return null;
        } catch (SharedEmissionFailure rejection) {
            checkEq(expectedMessage, rejection.getMessage(),
                "the empty-catalog STDLIB import [" + target + "]: the rejection"
                    + " message is the unchanged complete text");
            checkEq(expected, rejection.diagnosticRange(),
                "the empty-catalog STDLIB import [" + target + "]: the rejection"
                    + " carries the recorded MODULE_IMPORT's own exact range");
            return rejection;
        } catch (IllegalStateException other) {
            fail("the empty-catalog STDLIB import [" + target + "]: the rejection"
                + " is the source-bearing shared signal: " + other);
            return null;
        }
    }

    /**
     * Both shared targets reject the hand-built corrupted fact with the
     * unchanged complete message and the offending op's own exact range.
     */
    private static void assertSharedGuardOrigin(String what, SemanticOp offending,
            String expectedMessage, ExecutableLoweredProject project,
            SemanticLowerer.ProjectLoweringResult lowering, Fixture fixture)
            throws Exception {
        DiagnosticRange expected = offending.origin().span().range();
        check(expected.origin() == RangeOrigin.SOURCE && expected.scalarLength() > 0,
            what + ": the offending op carries a real SOURCE span: " + expected);
        for (String target : List.of("LuaJIT", "JVM")) {
            try {
                if ("JVM".equals(target)) {
                    JvmSemanticEmitter.emitProductionProject(project,
                        lowering.tables(), lowering.registries(), "FixtureProbe",
                        fixture.surface());
                } else {
                    LuaSemanticEmitter.emitProductionProject(project,
                        lowering.tables(), lowering.registries(), fixture.surface(),
                        null);
                }
                fail(what + " [" + target + "]: the emitter rejects");
            } catch (SharedEmissionFailure rejection) {
                checkEq(expectedMessage, rejection.getMessage(),
                    what + " [" + target + "]: the rejection message is the"
                        + " unchanged text");
                checkEq(expected, rejection.diagnosticRange(),
                    what + " [" + target + "]: the rejection carries the"
                        + " offending op's own exact range");
            } catch (IllegalStateException other) {
                fail(what + " [" + target + "]: the rejection is the"
                    + " source-bearing shared signal: " + other);
            }
        }
    }

    /**
     * The FIELD_READ whose required boundary child is removed from the
     * unit: the boundary-child lookup identifies the read op and carries
     * its own exact range on both targets.
     */
    private static void assertFieldBoundaryGuardOrigin(SemanticOp fieldRead,
            SemanticLowerer.ProjectLoweringResult lowering, Fixture fixture)
            throws Exception {
        LoweredModuleUnit unit = lowering.project()
            .modules().get(lowering.project().entryModule());
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            boolean childOfRead = op.kind() == SemanticOpKind.BOUNDARY
                && op.origin() != null
                && fieldRead.opId().equals(op.origin().parentOpId());
            if (!childOfRead) {
                ops.add(op);
            }
        }
        LoweredModuleUnit stripped = withUnitOps(unit, ops);
        Map<ModuleId, LoweredModuleUnit> modules =
            new LinkedHashMap<>(lowering.project().modules());
        modules.put(unit.moduleId(), stripped);
        ExecutableLoweredProject project = new ExecutableLoweredProject(
            lowering.project().semanticProfile(),
            lowering.project().interfaceIndex(), modules,
            lowering.project().entryModule());
        String expectedMessage = fieldRead.kind() + " " + fieldRead.opId()
            + " has no UNTYPED_CLASS_INPUT boundary child: the pinned field-op"
            + " shape carries it parented to the op (producer defect)";
        assertSharedGuardOrigin("the missing field boundary child", fieldRead,
            expectedMessage, project, lowering, fixture);
    }

    /** The project with one unit op rebuilt on a replaced payload. */
    private static ExecutableLoweredProject replaceUnitOp(
            ExecutableLoweredProject project, LoweredModuleUnit unit,
            SemanticOp op, KindPayload replacedPayload) {
        return replaceUnitOp(project, unit, op, replacedPayload, op.origin());
    }

    /**
     * The project with one unit op rebuilt on a replaced payload and a
     * replaced origin (the origin-transport control drives).
     */
    private static ExecutableLoweredProject replaceUnitOp(
            ExecutableLoweredProject project, LoweredModuleUnit unit,
            SemanticOp op, KindPayload replacedPayload,
            SourceOrigin replacedOrigin) {
        deal.semantic.ir.OperationContractSnapshot old = op.contract();
        deal.semantic.ir.OperationContractSnapshot rewired =
            new deal.semantic.ir.OperationContractSnapshot(old.version(),
                old.opKind(), old.resultType(), old.operandTypes(), old.selector(),
                replacedPayload, old.failurePolicy(), old.referencedSemanticIds(),
                old.canonicalDigest());
        List<SemanticOp> ops = new ArrayList<>();
        for (SemanticOp candidate : unit.ops()) {
            if (candidate.opId().equals(op.opId())) {
                ops.add(new SemanticOp(candidate.opId(), candidate.kind(),
                    replacedOrigin, candidate.result(), candidate.resultType(),
                    candidate.operands(), candidate.operandTypes(), replacedPayload,
                    candidate.failurePolicy(), rewired));
            } else {
                ops.add(candidate);
            }
        }
        Map<ModuleId, LoweredModuleUnit> modules =
            new LinkedHashMap<>(project.modules());
        modules.put(unit.moduleId(), withUnitOps(unit, ops));
        return new ExecutableLoweredProject(project.semanticProfile(),
            project.interfaceIndex(), modules, project.entryModule());
    }

    private static LoweredModuleUnit withUnitOps(LoweredModuleUnit unit,
            List<SemanticOp> ops) {
        return new LoweredModuleUnit(unit.formatVersion(), unit.semanticProfile(),
            unit.moduleId(), unit.interfaceHash(), unit.loweringContextHash(),
            unit.requiredCapabilities(), unit.constructCoverage(),
            unit.classLayouts(), unit.functions(), unit.moduleInit(),
            unit.exportPlan(), unit.functionBindings(), ops);
    }

    /** One emitter rejection of the doctored membership table, or null on no throw. */
    private static SharedEmissionFailure emitFailure(String target,
            ExecutableLoweredProject project,
            Map<ModuleId, StructuredBodyTable> tables,
            SemanticLowerer.ProjectLoweringResult lowering, Fixture fixture)
            throws Exception {
        try {
            if ("JVM".equals(target)) {
                JvmSemanticEmitter.emitProductionProject(project, tables,
                    lowering.registries(), "FixtureProbe", fixture.surface());
            } else {
                LuaSemanticEmitter.emitProductionProject(project, tables,
                    lowering.registries(), fixture.surface(), null);
            }
            return null;
        } catch (SharedEmissionFailure rejection) {
            return rejection;
        }
    }

    private static void testCrossModuleAsyncEmission(Fixture cross, Fixture same)
            throws Exception {
        System.out.println("-- the cross-module async call emits through the "
            + "entity-local async entry and executes; a same-module async call "
            + "emits --");

        Path crossOut = cross.root().resolve("out-luajit");
        {
            PublicationStager stager = PublicationStager.forRoot(crossOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(cross, Backend.LUAJIT, stager, false);
                check(result.emitted(),
                    "the cross-module async closure emits: "
                        + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the cross-module async artifact is the entry chunk");
                checkEq(1L, stager.stagedSet().relativePaths().stream()
                        .filter(path -> path.endsWith(".lua")
                            && !path.startsWith("std/")
                            && !path.equals("deal/runtime.lua")).count(),
                    "exactly one staged project artifact");
                stager.publish();
            } finally {
                stager.discard();
            }
            String chunk = Files.readString(crossOut.resolve("app.lua"),
                StandardCharsets.UTF_8);
            check(!chunk.contains("EXTERNAL_ASYNC_CALL"),
                "the emitted chunk carries no superseded guard token");
            check(!chunk.contains("SharedM"),
                "the emitted chunk references no per-module async-entry surface");
            check(chunk.contains("__asyncEntries[\"" + LIB.path()
                    + "#getValue\"]"),
                "the chunk carries one async entry per async export of the "
                    + "callee closure module");
            writeFileIn(cross.root(), "deal/runtime.lua",
                Files.readString(Path.of("deal/runtime.lua"), StandardCharsets.UTF_8));
            writeFileIn(cross.root(), "probe.lua", """
                local surfaces = dofile("out-luajit/app.lua")
                local worker = surfaces.worker
                assert(type(worker) == "table" and worker.__kind == "function",
                  "the entry surface publishes the cross-module async export")
                local handle = worker.f()
                assert(type(handle) == "table" and handle.__kind == "async",
                  "the published async export produces an async operation")
                require("deal.runtime").async_step(handle)
                local completion = handle.__result
                assert(handle.__done and completion == 42,
                  "the cross-module await completes with 42, got "
                    .. tostring(completion))
                print("PROBE|ASYNC-RESULT|" .. tostring(completion))
                """);
            ProcessOutcome luaRun = runProcess(List.of("luajit", "probe.lua"),
                cross.root());
            checkEq(0, luaRun.exitCode(),
                "the cross-module await path executes under luajit: "
                    + luaRun.output());
            check(luaRun.stdout().contains("PROBE|ASYNC-RESULT|42"),
                "the cross-module await completes with 42 under luajit: "
                    + luaRun.stdout());
        }

        // The JVM target of the same closure: the class source compiles
        // with javac --release 25 -proc:none and the runner executes the
        // exported async function through the artifact's published
        // surface.
        Path crossJvmOut = cross.root().resolve("out-jvm");
        Path crossJvmClasses = cross.root().resolve("cross-classes");
        {
            PublicationStager stager = PublicationStager.forRoot(crossJvmOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(cross, Backend.JVM, stager, false);
                check(result.emitted(),
                    "the cross-module async JVM closure emits: "
                        + result.diagnostics());
                stager.publish();
            } finally {
                stager.discard();
            }
            String source = Files.readString(crossJvmOut.resolve("App.java"),
                StandardCharsets.UTF_8);
            check(!source.contains("EXTERNAL_ASYNC_CALL"),
                "the emitted JVM class carries no superseded guard token");
            check(!source.contains("SharedM"),
                "the emitted JVM class references no per-module class");
            writeFileIn(crossJvmOut, "AsyncCrossRunner.java", """
                import deal.codegen.jvm.JvmRuntime;

                public final class AsyncCrossRunner {
                  public static void main(String[] args) {
                    App.main(new String[0]);
                    JvmRuntime.Table surface = App.EXPORT_SURFACES.get("app");
                    JvmRuntime.FunctionValue worker =
                        (JvmRuntime.FunctionValue) surface.read("worker");
                    Object completion = worker.fn.invoke(new Object[0]);
                    System.out.println("PROBE|ASYNC-RESULT|" + completion);
                  }
                }
                """);
            Files.createDirectories(crossJvmClasses);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", crossJvmClasses.toString(),
                crossJvmOut.resolve("App.java").toAbsolutePath().toString(),
                crossJvmOut.resolve("AsyncCrossRunner.java").toAbsolutePath().toString()),
                cross.root());
            checkEq(0, javacRun.exitCode(),
                "the cross-module async JVM artifact compiles: "
                    + javacRun.output());
            if (javacRun.exitCode() == 0) {
                ProcessOutcome jvmRun = runProcess(List.of("java", "-cp",
                    crossJvmClasses + java.io.File.pathSeparator + classpath,
                    "AsyncCrossRunner"), cross.root());
                checkEq(0, jvmRun.exitCode(),
                    "the cross-module await path executes under java: "
                        + jvmRun.output());
                check(jvmRun.stdout().contains("PROBE|ASYNC-RESULT|42"),
                    "the cross-module await completes with 42 under java: "
                        + jvmRun.stdout());
            }
        }

        // The same-module async call: accepted, emitted, and executed on
        // both targets. The awaiting call is reachable through the
        // exported zero-arity async function's published surface, so the
        // runner's invocation really runs the ASYNC_START(DEAL_BODY)/
        // AWAIT path (never merely compiling a never-invoked body).
        Path sameOut = same.root().resolve("out-luajit");
        {
            PublicationStager stager = PublicationStager.forRoot(sameOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(same, Backend.LUAJIT, stager, false);
                check(result.emitted(), "the same-module async closure emits: "
                    + result.diagnostics());
                checkEq("app.lua", result.artifactRelativePath(),
                    "the same-module async artifact is the entry chunk");
                stager.publish();
            } finally {
                stager.discard();
            }
            check(Files.isRegularFile(sameOut.resolve("app.lua")),
                "the published same-module async chunk exists");
            writeFileIn(same.root(), "deal/runtime.lua",
                Files.readString(Path.of("deal/runtime.lua"), StandardCharsets.UTF_8));
            writeFileIn(same.root(), "probe.lua", """
                local surfaces = dofile("out-luajit/app.lua")
                assert(type(surfaces) == "table",
                  "the chunk returns the entry surface")
                local worker = surfaces.worker
                assert(type(worker) == "table" and worker.__kind == "function",
                  "the entry surface publishes the same-module async export")
                local handle = worker.f()
                assert(type(handle) == "table" and handle.__kind == "async",
                  "the published async export produces an async operation")
                require("deal.runtime").async_step(handle)
                local completion = handle.__result
                assert(handle.__done and completion == 1,
                  "the awaiting call completes with 1, got "
                    .. tostring(completion))
                print("PROBE|ASYNC-RESULT|" .. tostring(completion))
                """);
            ProcessOutcome luaRun = runProcess(List.of("luajit", "probe.lua"),
                same.root());
            checkEq(0, luaRun.exitCode(),
                "the same-module await path executes under luajit: "
                    + luaRun.output());
            check(luaRun.stdout().contains("PROBE|ASYNC-RESULT|1"),
                "the awaiting call completes with 1 under luajit: "
                    + luaRun.stdout());
        }

        // The JVM target of the same fixture: the class source compiles
        // with javac --release 25 -proc:none and the runner executes the
        // exported async function through the artifact's published
        // surface.
        Path jvmOut = same.root().resolve("out-jvm");
        Path jvmClasses = same.root().resolve("same-classes");
        {
            PublicationStager stager = PublicationStager.forRoot(jvmOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(same, Backend.JVM, stager, false);
                check(result.emitted(),
                    "the same-module async JVM closure emits: "
                        + result.diagnostics());
                stager.publish();
            } finally {
                stager.discard();
            }
            check(Files.isRegularFile(jvmOut.resolve("App.java")),
                "the published same-module async JVM artifact exists");
            writeFileIn(jvmOut, "AsyncWorkerRunner.java", """
                import deal.codegen.jvm.JvmRuntime;

                public final class AsyncWorkerRunner {
                  public static void main(String[] args) {
                    App.main(new String[0]);
                    JvmRuntime.Table surface = App.EXPORT_SURFACES.get("app");
                    JvmRuntime.FunctionValue worker =
                        (JvmRuntime.FunctionValue) surface.read("worker");
                    Object completion = worker.fn.invoke(new Object[0]);
                    System.out.println("PROBE|ASYNC-RESULT|" + completion);
                  }
                }
                """);
            Files.createDirectories(jvmClasses);
            String classpath = absoluteClasspath();
            ProcessOutcome javacRun = runProcess(List.of("javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", jvmClasses.toString(),
                jvmOut.resolve("App.java").toAbsolutePath().toString(),
                jvmOut.resolve("AsyncWorkerRunner.java").toAbsolutePath().toString()),
                same.root());
            checkEq(0, javacRun.exitCode(),
                "the same-module async JVM artifact compiles: "
                    + javacRun.output());
            if (javacRun.exitCode() == 0) {
                ProcessOutcome jvmRun = runProcess(List.of("java", "-cp",
                    jvmClasses + java.io.File.pathSeparator + classpath,
                    "AsyncWorkerRunner"), same.root());
                checkEq(0, jvmRun.exitCode(),
                    "the same-module await path executes under java: "
                        + jvmRun.output());
                check(jvmRun.stdout().contains("PROBE|ASYNC-RESULT|1"),
                    "the awaiting call completes with 1 under java: "
                        + jvmRun.stdout());
            }
        }
    }

    // =========================================================================
    // 6. The source-map emission: real coordinates, explicit flag, rollback
    // =========================================================================

    private static void testSourceMapEmission(Fixture fixture, Fixture failing,
            UnmappedLua unmapped) throws Exception {
        System.out.println("-- an effective --source-map emits source comments and "
            + "one sidecar per constituent module with real generated/source "
            + "coordinates on both targets; no flag emits neither; a failing run "
            + "preserves the previous set byte-for-byte --");

        // (1) LuaJIT: the artifact's source comments and each constituent
        // module's sidecar mapping agree with the real artifact and source
        // coordinates, and no warning text is printed anymore.
        Path out = fixture.root().resolve("source-map-out");
        String lua;
        Sidecar appMap;
        Sidecar libMap;
        {
            PublicationStager stager = PublicationStager.forRoot(out);
            try {
                String stderr = emitCapturingStderr(fixture, Backend.LUAJIT,
                    stager, true).stderr();
                checkEq("", stderr,
                    "the effective --source-map run prints no warning");
                check(stager.stagedSet().artifact("app.lua").isPresent(),
                    "the source-map run stages the entry module's chunk");
                check(stager.stagedSet().artifact("app.deal.map.json").isPresent(),
                    "the entry module's sidecar stages with the chunk");
                check(stager.stagedSet().artifact("lib.deal.map.json").isPresent(),
                    "the constituent library module's sidecar stages too");
                lua = new String(stager.stagedSet().artifact("app.lua")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
                appMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("app.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
                libMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("lib.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
            } finally {
                stager.discard();
            }
        }
        List<String> luaLines = lua.lines().toList();
        check(appMap.generated().endsWith("/source-map-out/app.lua"),
            "the entry sidecar's generated path is the shared artifact: "
                + appMap.generated());
        check(libMap.generated().equals(appMap.generated()),
            "every constituent sidecar maps the same shared artifact: "
                + libMap.generated());
        verifySidecarCoordinates(appMap, luaLines, APP_SOURCE, "-- ",
            "src/app.deal");
        verifySidecarCoordinates(libMap, luaLines, LIB_SOURCE, "-- ",
            "src/lib.deal");
        check(hasMappingAtSourceLine(appMap, 4),
            "the entry sidecar maps the main body's return null");
        check(hasMappingAtSourceLine(appMap, 8),
            "the entry sidecar maps the label body's return");
        check(hasMappingAtSourceLine(libMap, 2),
            "the library sidecar maps add's return a + b");
        check(hasMappingAtSourceLine(libMap, 6),
            "the library sidecar maps twice's return x * 2");
        checkEq(countSourceComments(lua, "-- "),
            appMap.mappings().size() + libMap.mappings().size(),
            "every recorded mapping is one emitted source comment of the chunk");

        // (2) The same project without the effective flag: no warning, no
        // sidecar, and no source comment in the artifact bytes.
        check(unmapped.paths().stream()
                .noneMatch(path -> path.endsWith(".deal.map.json")),
            "a compile without the effective flag stages no sidecar");
        checkEq("", unmapped.stderr(),
            "a compile without the flag prints no warning");
        checkEq(0, countSourceComments(unmapped.source(), "-- "),
            "a compile without the flag emits no source comment");

        // (3) JVM: the class source carries the same source comments and the
        // two sidecars map its real generated coordinates.
        Path jvmOut = fixture.root().resolve("jvm-map-out");
        String javaSource;
        Sidecar jvmAppMap;
        Sidecar jvmLibMap;
        {
            PublicationStager stager = PublicationStager.forRoot(jvmOut);
            try {
                String stderr = emitCapturingStderr(fixture, Backend.JVM,
                    stager, true).stderr();
                checkEq("", stderr,
                    "the JVM effective --source-map run prints no warning");
                check(stager.stagedSet().artifact("App.java").isPresent(),
                    "the JVM source-map run stages the one entry class");
                javaSource = new String(stager.stagedSet().artifact("App.java")
                    .orElseThrow().content(), StandardCharsets.UTF_8);
                jvmAppMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("app.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
                jvmLibMap = parseSidecar(new String(stager.stagedSet()
                    .artifact("lib.deal.map.json").orElseThrow().content(),
                    StandardCharsets.UTF_8));
            } finally {
                stager.discard();
            }
        }
        List<String> javaLines = javaSource.lines().toList();
        verifySidecarCoordinates(jvmAppMap, javaLines, APP_SOURCE, "// ",
            "src/app.deal");
        verifySidecarCoordinates(jvmLibMap, javaLines, LIB_SOURCE, "// ",
            "src/lib.deal");
        checkEq(countSourceComments(javaSource, "// "),
            jvmAppMap.mappings().size() + jvmLibMap.mappings().size(),
            "every recorded mapping is one emitted source comment of the class");

        // (4) Rollback: a failing run into a root holding a previously
        // published set (artifact and maps) leaves that set byte-identical.
        Path rollbackOut = fixture.root().resolve("rollback-out");
        writeFileIn(rollbackOut, "app.lua", "-- previous artifact\n");
        writeFileIn(rollbackOut, "app.deal.map.json", "-- previous map\n");
        writeFileIn(rollbackOut, "lib.deal.map.json", "-- previous map\n");
        writeFileIn(rollbackOut, "deal/runtime.lua", "-- previous runtime\n");
        Map<String, String> before = snapshotTree(rollbackOut);
        {
            PublicationStager stager = PublicationStager.forRoot(rollbackOut);
            ProductionProjectEmission.Result result;
            try {
                result = emit(failing, Backend.LUAJIT, stager, true);
                check(!result.emitted(),
                    "the failing source-map run emits nothing");
                check(stager.stagedSet().relativePaths().isEmpty()
                        || stager.stagedSet().relativePaths().stream()
                            .noneMatch(path -> path.endsWith(".deal.map.json")),
                    "the failing source-map run stages no sidecar");
            } finally {
                stager.discard();
            }
            checkEq(before, snapshotTree(rollbackOut),
                "the failing source-map run preserves the previous set "
                    + "(artifact and maps) byte-identical");
        }
    }

    /** One parsed source-map sidecar (the spec's pinned document shape). */
    private record Sidecar(String source, String generated,
                           List<MapEntry> mappings) {
    }

    /** One mapping entry of a sidecar. */
    private record MapEntry(int generatedLine, int generatedColumn,
                            int sourceLine, int sourceColumn) {
    }

    /**
     * Parses one {@code .deal.map.json} document through the strict canonical
     * JSON transport and verifies its pinned fields (version, source,
     * generated, mapping shape).
     */
    private static Sidecar parseSidecar(String json) {
        CanonicalJson.Obj root;
        try {
            root = (CanonicalJson.Obj) CanonicalJson.parse(json);
        } catch (RuntimeException malformed) {
            fail("the sidecar is not valid JSON: " + malformed);
            return new Sidecar("", "", List.of());
        }
        Map<String, CanonicalJson.Value> fields = new LinkedHashMap<>();
        for (CanonicalJson.Entry entry : root.entries()) {
            fields.put(entry.key(), entry.value());
        }
        checkEq(1, ((CanonicalJson.Int) fields.get("version")).value(),
            "the sidecar version is the pinned 1");
        String source = ((CanonicalJson.Str) fields.get("source")).value();
        String generated = ((CanonicalJson.Str) fields.get("generated")).value();
        List<MapEntry> mappings = new ArrayList<>();
        for (CanonicalJson.Value item
                : ((CanonicalJson.Arr) fields.get("mappings")).items()) {
            Map<String, CanonicalJson.Value> entryFields = new LinkedHashMap<>();
            for (CanonicalJson.Entry entry
                    : ((CanonicalJson.Obj) item).entries()) {
                entryFields.put(entry.key(), entry.value());
            }
            mappings.add(new MapEntry(
                ((CanonicalJson.Int) entryFields.get("generatedLine")).value(),
                ((CanonicalJson.Int) entryFields.get("generatedColumn")).value(),
                ((CanonicalJson.Int) entryFields.get("sourceLine")).value(),
                ((CanonicalJson.Int) entryFields.get("sourceColumn")).value()));
        }
        return new Sidecar(source, generated, mappings);
    }

    /** Whether one sidecar records a mapping at the given source line. */
    private static boolean hasMappingAtSourceLine(Sidecar sidecar,
            int sourceLine) {
        return sidecar.mappings().stream()
            .anyMatch(mapping -> mapping.sourceLine() == sourceLine);
    }

    /**
     * Verifies one sidecar against the real artifact and the module's real
     * source file: every mapped position is inside both texts, and the mapped
     * artifact line is exactly the source comment carrying the mapped source
     * coordinates under the module's one display name.
     */
    private static void verifySidecarCoordinates(Sidecar sidecar,
            List<String> artifactLines, String sourceText, String commentMarker,
            String expectedSourceSuffix) {
        check(sidecar.source().endsWith(expectedSourceSuffix),
            "the sidecar names the constituent source file: " + sidecar.source());
        check(!sidecar.mappings().isEmpty(),
            "the sidecar of " + sidecar.source() + " carries mappings");
        List<String> sourceLines = sourceText.lines().toList();
        for (MapEntry mapping : sidecar.mappings()) {
            check(mapping.generatedLine() >= 1
                    && mapping.generatedLine() <= artifactLines.size(),
                "the mapped generated line is inside the artifact: " + mapping);
            check(mapping.sourceLine() >= 1
                    && mapping.sourceLine() <= sourceLines.size(),
                "the mapped source line addresses the real source file: "
                    + mapping);
            if (mapping.sourceLine() >= 1
                    && mapping.sourceLine() <= sourceLines.size()) {
                int lineLength = sourceLines.get(mapping.sourceLine() - 1).length();
                check(mapping.sourceColumn() >= 1
                        && mapping.sourceColumn() <= lineLength + 1,
                    "the mapped source column addresses real source text: "
                        + mapping);
            }
            if (mapping.generatedLine() >= 1
                    && mapping.generatedLine() <= artifactLines.size()) {
                String generatedLine = artifactLines.get(
                    mapping.generatedLine() - 1);
                checkEq(commentMarker + sidecar.source() + ":"
                        + mapping.sourceLine() + ":" + mapping.sourceColumn(),
                    generatedLine.strip(),
                    "the mapped artifact line is the mapping's source comment");
                checkEq(generatedLine.indexOf(commentMarker) + 1,
                    mapping.generatedColumn(),
                    "the mapped generated column addresses the comment start");
            }
        }
    }

    /** The emitted source comments of one artifact text. */
    private static int countSourceComments(String artifact, String commentMarker) {
        int count = 0;
        for (String line : artifact.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith(commentMarker)
                    && stripped.matches(".*/[^ ]*\\.deal:[0-9]+:[0-9]+")) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** The published/previous artifact set of one tree: path → bytes. */
    private static Map<String, String> snapshotTree(Path root) throws Exception {
        Map<String, String> snapshot = new java.util.TreeMap<>();
        if (!Files.exists(root)) {
            return snapshot;
        }
        try (var walk = Files.walk(root)) {
            for (Path file : walk.sorted().toList()) {
                if (Files.isRegularFile(file)) {
                    snapshot.put(root.relativize(file).toString().replace('\\', '/'),
                        java.util.Arrays.toString(Files.readAllBytes(file)));
                }
            }
        }
        return snapshot;
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

        String output() {
            return "stdout=" + stdout.replace("\n", "\\n")
                + " stderr=" + stderr.replace("\n", "\\n");
        }
    }

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
        BoundedProcess.BoundedResult result = BoundedProcess.runCaptured(
            command, workDir);
        return new ProcessOutcome(result.exitCode(), result.stdout(),
            result.stderr());
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
        System.out.println("=== Production Project Emission Tests (ISSUE-0642) ===\n");
        Fixture fixture = null;
        Fixture cross = null;
        Fixture same = null;
        Fixture failing = null;
        try {
            fixture = twoModuleFixture(APP_SOURCE);
            testUnitShapeAndConstants(fixture);
            UnmappedLua unmapped = testOneArtifactPerTarget(fixture);
            testOneMainProbe();
            testJvmToolchain(fixture);
            failing = adapterSourceFixture();
            testAtomicFailures(failing);
            testHostImportGuard();
            testFfiProviderGapOrigin();
            testRejectedOpEmissionOrigins();
            testIdentifiedSharedGuardOrigins();
            testAbsentDeclarationSurfaceEntries();
            testStdlibCatalogMissOrigin();
            testDeclarationSignatureOrigin();
            cross = asyncFixture(ASYNC_LIB_SOURCE, CROSS_ASYNC_APP_SOURCE);
            same = asyncFixture(ASYNC_LIB_SOURCE, SAME_ASYNC_APP_SOURCE);
            testCrossModuleAsyncEmission(cross, same);
            testSourceMapEmission(fixture, failing, unmapped);
        } finally {
            deleteRecursively(failing == null ? null : failing.root());
            deleteRecursively(same == null ? null : same.root());
            deleteRecursively(cross == null ? null : cross.root());
            deleteRecursively(fixture == null ? null : fixture.root());
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Production Project Emission Tests Passed ===");
    }
}
