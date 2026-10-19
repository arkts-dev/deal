package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.SharedEmissionFailure;
import deal.codegen.SourceMapGenerator;
import deal.codegen.SourceMapPaths;
import deal.codegen.js.JsEmissionInput;
import deal.codegen.js.JsModuleArtifact;
import deal.codegen.js.JsProjectEmission;
import deal.codegen.js.JsSemanticEmitter;
import deal.codegen.js.JsSourceFile;
import deal.codegen.js.RuntimeDeclarationProjection;
import deal.codegen.js.RuntimeDeclarationSurface;
import deal.ffi.FfiGeneratedModule;
import deal.identity.CanonicalModuleIdentity;
import deal.module.CompilationOrchestrator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CheckedModuleInput;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CheckedProjectInput;
import deal.semantic.ClassRegistrationSeeds;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.HostDeclarationSurface;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRequirementManifest;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.ModuleImportKind;
import deal.semantic.ir.NamespaceRegistrations;
import deal.semantic.ir.RuntimeDescriptor;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.StructuredBodyTable;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The JavaScript semantic-IR renderer acceptance drives: the ISSUE-0798
 * foundation (one artifact per implementation unit in dependency order,
 * the structural declaration projection, local value/cell/control
 * execution under the real Node binary, the nullable equality pair's
 * shared nil-equivalence rule, the seeded intrinsic carrier's single
 * materialization and reference identity, the array hole decision at the
 * contextual boundary, the adapter's forwarded call origin, the
 * carrier-neutral module-namespace index realization, the private module
 * registry's repeat identity and sticky failure, coordinate-only maps, the
 * malformed/no-frontend seam assertions, independent determinism, and the
 * fail-closed unsupported-slice contract with the offending operation's own
 * origin), the ISSUE-0799 class drive (the per-attempt defaults,
 * imported-provider construction, field commits, the public helper ABI and
 * the JSON walkers), its ISSUE-0811 public-role acceptance (the exact
 * META/C$new/C$fromJson/C$toJson/C$fields keys and signatures, C$new's
 * origin absence and unmutated provided input, the untagged defaults-only
 * factory transfer with no default at registration, and the optional-null
 * three states through the public helpers), and the ISSUE-0800 call/boundary
 * realization (native
 * {@code ASYNC_START}/{@code AWAIT} under real Promises, the async adapter
 * and cross-module async entry, the direct-run/require entry split with
 * the uncaught-main presentation, the {@code $rt.invokeAsyncExport} legs
 * with their exact per-invocation counts, the awaited failure transport
 * across modules, and the host-driven callback crossing), and the
 * ISSUE-0801 HOST/STDLIB boundary (one raw Node require per resolved host
 * file, the occurrence-owned typed import surfaces with their name-sorted
 * declared validation and first-failure stop, the unlocated raw-shape
 * guard under require and direct execution, the deployed stdlib surface
 * behind the relative require, and the host-declaration class
 * construction), and the ISSUE-0802 source mapping (the one shared
 * {@code SourceMapRecorder} recording during emission, the local/foreign
 * placement through the recorded parent op with no invented coordinate, the
 * wrapper/entry groups at their local declaration sites, one strict
 * version-1 {@code <module>.deal.map.json} per implementation artifact with
 * the owner's {@code SourceMapPaths} live-root projection, single-source
 * bounds, the supplementary-Unicode path roundtrip, the empty list and the
 * internal map-off rendering, and the independent byte-identical rebuild).
 */
public class JsSemanticEmitterTest {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

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

    private static void skip(String reason) {
        skipped++;
        System.out.println("  SKIP: " + reason);
    }

    private static boolean nodeAvailable() {
        try {
            Process node = new ProcessBuilder("node", "--version")
                .redirectErrorStream(true).start();
            node.getInputStream().readAllBytes();
            return node.waitFor() == 0;
        } catch (IOException | InterruptedException ignored) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        boolean node = nodeAvailable();
        System.out.println(node
            ? "-- node available: the real-artifact drives run --"
            : "-- node NOT available: the real-artifact drives are skipped --");
        Path tmpRoot = Files.createTempDirectory("js-semantic-emitter");
        try {
            testArtifactSetAndDeterminism(tmpRoot);
            testSourceMapCoordinates(tmpRoot);
            testSourceMapSidecars(tmpRoot);
            testSourceMapForeignPlacement(tmpRoot, node);
            testValuesContainersAndControl(tmpRoot, node);
            testNullableEqualityNilEquivalence(tmpRoot, node);
            testCellsClosuresAndAdapters(tmpRoot, node);
            testIntrinsicValueCarriers(tmpRoot, node);
            testArrayHoleReadDecision(tmpRoot, node);
            testAdapterCallOrigin(tmpRoot, node);
            testModuleNamespaceIndexCarrier(tmpRoot, node);
            testFailureTuples(tmpRoot, node);
            testClassConstructionAndJson(tmpRoot, node);
            testFieldJsonExecutionAndCreationSites(tmpRoot, node);
            testModuleRegistry(tmpRoot, node);
            testAsyncCallsAndCompletion(tmpRoot, node);
            testAwaitedFailureTransport(tmpRoot, node);
            testAsyncExportAbiAndEntryLegs(tmpRoot, node);
            testCallbackCrossing(tmpRoot, node);
            testDynamicCalleeDispatch(tmpRoot, node);
            testAdapterPerExecutionSource(tmpRoot, node);
            testHostParameterCellAndDeferredReads(tmpRoot, node);
            testHostOccurrenceSurfaces(tmpRoot, node);
            testHostDeclaredOrderAndRawShape(tmpRoot, node);
            testStdlibSurfaceAndCalls(tmpRoot, node);
            testDeclarationProjection(tmpRoot);
            testFailClosedAndSeamPurity(tmpRoot);
        } finally {
            deleteRecursively(tmpRoot);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed
            + ", Skipped: " + skipped);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== JS Semantic-IR Emitter Tests Passed ===");
    }

    // =========================================================================
    // The real pipeline harness
    // =========================================================================

    /** One compiled, lowered, admitted rendering input. */
    private record Fixture(
        Path root,
        CheckedProjectInput checkedProject,
        ExecutableLoweredProject project,
        Map<ModuleId, StructuredBodyTable> tables,
        Map<ModuleId, ClassFactoryRegistry> registries,
        ClassRegistrationSeeds seeds,
        NamespaceRegistrations namespaces,
        HostDeclarationSurface surface,
        Map<ModuleId, CanonicalModuleIdentity> identities) {

        JsEmissionInput emissionInput(boolean sourceMap) {
            return emissionInput(sourceMap, root.resolve("out").toAbsolutePath());
        }

        /**
         * The rendering input whose owner-supplied source display names are
         * the {@link SourceMapPaths} project-relative projection against the
         * live output root — exactly the strings the production owner hands
         * the renderer and the sidecars (`ProductionProjectEmission`).
         */
        JsEmissionInput emissionInput(boolean sourceMap, Path liveOutputRoot) {
            Map<ModuleId, JsSourceFile> sourceFiles = new LinkedHashMap<>();
            for (CheckedModuleInput module : checkedProject.modules()) {
                if (module.kind() != deal.semantic.CheckedModuleKind
                        .IMPLEMENTATION) {
                    continue;
                }
                sourceFiles.put(module.moduleId(), new JsSourceFile(
                    module.sourceId(), SourceMapPaths.sourceDisplay(
                        module.sourceId(), liveOutputRoot)));
            }
            return new JsEmissionInput(project, tables, registries, seeds,
                namespaces, RuntimeDeclarationProjection.project(surface,
                    identities, seeds), sourceFiles, sourceMap);
        }

        /** One implementation unit's authoritative source id. */
        String sourceId(ModuleId module) {
            for (CheckedModuleInput candidate : checkedProject.modules()) {
                if (candidate.moduleId().equals(module)) {
                    return candidate.sourceId();
                }
            }
            throw new IllegalStateException("no source id for " + module);
        }

        /** One implementation unit's source file path. */
        Path sourcePath(ModuleId module) {
            for (CheckedModuleInput candidate : checkedProject.modules()) {
                if (candidate.moduleId().equals(module)) {
                    return candidate.sourcePath();
                }
            }
            throw new IllegalStateException("no source path for " + module);
        }
    }

    private static Fixture compileFixture(Path root, String appSource)
            throws Exception {
        return compileFixture(root, appSource, Map.of(), Map.of());
    }

    private static Fixture compileFixture(Path root, String appSource,
            Map<String, String> extraSources) throws Exception {
        return compileFixture(root, appSource, extraSources, Map.of());
    }

    private static Fixture compileFixture(Path root, String appSource,
            Map<String, String> extraSources,
            Map<String, String> externals) throws Exception {
        return compileFixture(root, appSource, extraSources, externals, Set.of());
    }

    /**
     * Compiles one fixture project through the production frontend, lowers
     * it once, and produces the seeds/namespaces/declaration surface of the
     * canonical rendering input.
     */
    private static Fixture compileFixture(Path root, String appSource,
            Map<String, String> extraSources,
            Map<String, String> externals,
            Set<String> callbackExports) throws Exception {
        return compileFixtureAt(root, root.resolve("src"), appSource,
            extraSources, externals, callbackExports);
    }

    /** One fixture whose sources live in the {@code src} directory. */
    private static Fixture compileFixtureAt(Path root, Path sourceDir,
            String appSource, Map<String, String> extraSources)
            throws Exception {
        return compileFixtureAt(root, sourceDir, appSource, extraSources,
            Map.of(), Set.of());
    }

    /**
     * Compiles one fixture project from an explicit source directory through
     * the production frontend, lowers it once, and produces the
     * seeds/namespaces/declaration surface of the canonical rendering input.
     * The source directory is what the sidecar and comment display names are
     * projected from, so a fixture can carry any path text the owner may
     * legitimately supply (a supplementary-Unicode directory included).
     */
    private static Fixture compileFixtureAt(Path root, Path sourceDir,
            String appSource, Map<String, String> extraSources,
            Map<String, String> externals,
            Set<String> callbackExports) throws Exception {
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("app.deal"), appSource,
            StandardCharsets.UTF_8);
        for (Map.Entry<String, String> extra : extraSources.entrySet()) {
            // The extra sources are project-root-relative paths, exactly the
            // spelling the fixture harness always accepted.
            Path target = root.resolve(extra.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, extra.getValue(), StandardCharsets.UTF_8);
        }
        Path entry = sourceDir.resolve("app.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, output, false, false, false, false, Backend.LUAJIT,
            externals, List.of(sourceDir.toAbsolutePath()),
            Path.of(".").toAbsolutePath().normalize(), null,
            invocation());
        boolean compiled = orchestrator.compile();
        if (!compiled) {
            throw new IllegalStateException("the fixture project did not"
                + " compile: " + orchestrator.diagnostics());
        }
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.hasErrors() || built.input() == null
                || built.index() == null || manifests == null
                || manifests.hasErrors() || manifests.manifests() == null) {
            throw new IllegalStateException("the fixture project produced no"
                + " checked closure and manifest set");
        }
        Map<ModuleId, FfiGeneratedModule> externC = new LinkedHashMap<>();
        for (Map.Entry<String, FfiGeneratedModule> generated
                : orchestrator.ffiGenerations().entrySet()) {
            externC.put(new ModuleId(generated.getKey()), generated.getValue());
        }
        Map<ModuleId, CanonicalModuleIdentity> identities = new LinkedHashMap<>();
        for (ModuleId declarationModule
                : orchestrator.hostDeclarationSurface().moduleIds()) {
            identities.put(declarationModule,
                declarationIdentityOf(declarationModule, externals));
        }
        SemanticLowerer.ProjectLoweringResult lowering;
        if (identities.isEmpty() && callbackExports.isEmpty()
                && built.input().modules().get(0).moduleId()
                    .equals(built.input().entryModule())) {
            ExecutableLoweredProject project = orchestrator.producedProject();
            Map<ModuleId, StructuredBodyTable> tables = new LinkedHashMap<>();
            Map<ModuleId, ClassFactoryRegistry> registries = new LinkedHashMap<>();
            for (LoweredModuleUnit unit : project.modules().values()) {
                tables.put(unit.moduleId(), unit.bodyTable());
                registries.put(unit.moduleId(), unit.classFactories());
            }
            NamespaceRegistrations.Assembly assembly = NamespaceRegistrations
                .assemble(List.copyOf(project.modules().values()));
            if (assembly.hasErrors()) {
                throw new IllegalStateException("the fixture project produced no"
                    + " namespace registrations: " + assembly.diagnostics());
            }
            lowering = new SemanticLowerer.ProjectLoweringResult(project,
                tables, registries, project.classSeeds(),
                assembly.registrations(), List.of());
        } else {
            lowering = SemanticLowerer.lowerProject(invocation(), built.input(),
                built.index(), manifests.manifests(),
                orchestrator.hostDeclarationSurface(), identities, externC,
                BuiltinErrorDeclaration.synthesized(
                    built.input().modules().get(0).ast().span()),
                List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                    IntrinsicKind.BYTES_NEW), callbackExports);
        }
        if (lowering.hasErrors() || lowering.project() == null) {
            throw new IllegalStateException("the fixture project did not"
                + " lower: " + lowering.diagnostics());
        }
        return new Fixture(root, built.input(), lowering.project(),
            lowering.tables(), lowering.registries(), lowering.seeds(),
            lowering.namespaces(), orchestrator.hostDeclarationSurface(),
            identities);
    }

    /**
     * The compilation's declaration-module identity: the externals key the
     * manifest resolves the declaration through, exactly as written (the
     * production owner's single module-path classification). A declaration
     * module without an externals entry (no host fixture) keeps the
     * standalone harness identity.
     */
    private static CanonicalModuleIdentity declarationIdentityOf(
            ModuleId declarationModule, Map<String, String> externals) {
        for (String specifier : externals.keySet()) {
            if (specifier.equals(declarationModule.path())
                    || specifier.replace('/', '.')
                        .equals(declarationModule.path())) {
                return new CanonicalModuleIdentity.ExternalModule(specifier);
            }
        }
        return IdentityTestFixtures.moduleIdentityOf(declarationModule.path());
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(
            deal.semantic.ir.ReleaseState.V1_3_ACTIVE,
            CapabilityRegistry.releaseRegistry());
    }

    private static void writeFileIn(Path root, String relative, String content)
            throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /**
     * Renders one fixture's emission and stages the artifacts (plus the one
     * runtime copy and the spec-listed stdlib implementation copies the
     * project imports) into a fresh output directory under {@code root}.
     * The renderer itself writes nothing; the drive performs the staging the
     * production owner performs.
     */
    private static Path stage(Fixture fixture, String name, boolean sourceMap)
            throws IOException {
        return stage(fixture, name, JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(sourceMap)));
    }

    private static Path stage(Fixture fixture, String name,
            JsProjectEmission emission) throws IOException {
        Path out = fixture.root().resolve("emit-" + name);
        for (JsModuleArtifact artifact : emission.modules()) {
            Path target = out.resolve(artifact.artifactRelativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, artifact.source(), StandardCharsets.UTF_8);
        }
        Path runtimeDir = out.resolve("deal");
        Files.createDirectories(runtimeDir);
        Files.copy(Path.of("deal/runtime.js"), runtimeDir.resolve("runtime.js"),
            StandardCopyOption.REPLACE_EXISTING);
        for (String stdlibModule : importedStdlibModules(fixture)) {
            Path target = out.resolve("std/" + stdlibModule + ".js");
            Files.createDirectories(target.getParent());
            Files.copy(Path.of("std/" + stdlibModule + ".js"), target,
                StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(out.resolve("entry.txt"),
            emission.entryArtifactRelativePath(), StandardCharsets.UTF_8);
        return out;
    }

    /** The spec-listed stdlib modules one fixture's project imports. */
    private static List<String> importedStdlibModules(Fixture fixture) {
        Set<String> modules = new java.util.LinkedHashSet<>();
        for (LoweredModuleUnit unit : fixture.project().modules().values()) {
            for (SemanticOp op : unit.ops()) {
                if (op.kind() != SemanticOpKind.MODULE_IMPORT
                        || !(op.payload()
                            instanceof KindPayload.ModuleImportPayload payload)
                        || payload.kind() != ModuleImportKind.STDLIB) {
                    continue;
                }
                modules.add(payload.resolvedModule().path()
                    .substring("std.".length()));
            }
        }
        return List.copyOf(modules);
    }

    private static String entryPath(Path out) throws IOException {
        return Files.readString(out.resolve("entry.txt"), StandardCharsets.UTF_8);
    }

    /** Runs the emitted entry (or a driver) under the real Node binary. */
    private record NodeRun(int status, String output) {
    }

    private static NodeRun runNode(Path dir, String script) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node", script);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process node = builder.start();
        String output = new String(node.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int status = node.waitFor();
        return new NodeRun(status, output);
    }

    /** Runs a driver under node with the two streams captured separately. */
    private record NodeStreams(int status, String stdout, String stderr) {
    }

    private static NodeStreams runNodeStreams(Path dir, String script)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node", script);
        builder.directory(dir.toFile());
        Process node = builder.start();
        byte[][] captured = new byte[2][];
        Thread stdout = new Thread(() -> {
            try {
                captured[0] = node.getInputStream().readAllBytes();
            } catch (IOException ignored) {
                captured[0] = new byte[0];
            }
        });
        Thread stderr = new Thread(() -> {
            try {
                captured[1] = node.getErrorStream().readAllBytes();
            } catch (IOException ignored) {
                captured[1] = new byte[0];
            }
        });
        stdout.start();
        stderr.start();
        int status = node.waitFor();
        stdout.join();
        stderr.join();
        return new NodeStreams(status,
            new String(captured[0], StandardCharsets.UTF_8),
            new String(captured[1], StandardCharsets.UTF_8));
    }

    // =========================================================================
    // 1. The artifact set, order, and determinism
    // =========================================================================

    private static void testArtifactSetAndDeterminism(Path tmpRoot)
            throws Exception {
        System.out.println("-- one artifact per implementation unit in"
            + " dependency order, empty units included, none for declarations"
            + " --");
        Path root = Files.createTempDirectory(tmpRoot, "artifacts");
        Fixture fixture = compileFixture(root, """
            import * as util from "./util";
            import * as empty from "./empty";

            export function main(): null {
              let y: int = util.addOne(2);
              if (y !== 3) { throw { code: "TEST_FAIL", message: "cross" }; }
              return null;
            }
            """, Map.of(
                "src/util.deal", """
                    export function addOne(x: int): int {
                      return x + 1;
                    }
                    """,
                "src/empty.deal", """
                    // an implementation unit with no statements
                    """), Map.of());

        checkEq(List.of(new ModuleId("util"), new ModuleId("empty"),
                new ModuleId("app")),
            List.copyOf(fixture.project().modules().keySet()),
            "the module closure is the dependency order");

        JsProjectEmission first =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        JsProjectEmission second =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        checkEq("app.js", first.entryArtifactRelativePath(),
            "the entry artifact path names the entry module's member");
        checkEq(List.of("util.js", "empty.js", "app.js"),
            first.modules().stream().map(JsModuleArtifact::artifactRelativePath)
                .toList(),
            "one artifact per implementation unit at the module-relative .js"
                + " path, in dependency order");
        checkEq(3, first.modules().size(),
            "the closure's three implementation units produce three artifacts");
        checkEq(List.of(new ModuleId("util"), new ModuleId("empty"),
                new ModuleId("app")),
            first.modules().stream().map(JsModuleArtifact::moduleId).toList(),
            "each artifact carries its own module identity");
        for (int i = 0; i < first.modules().size(); i++) {
            checkEq(first.modules().get(i).source(),
                second.modules().get(i).source(),
                "the " + first.modules().get(i).artifactRelativePath()
                    + " emission is byte-identical across independent calls");
            check(first.modules().get(i).mappings() == null,
                "maps are ineffective without the effective map selection");
        }
        JsProjectEmission mapped =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(true));
        for (JsModuleArtifact artifact : mapped.modules()) {
            check(artifact.mappings() != null,
                "the effective map selection gives every artifact an ordered"
                    + " list (" + artifact.artifactRelativePath() + ")");
            if (artifact.moduleId().equals(new ModuleId("empty"))) {
                check(artifact.source().contains("$rt.initModule(\"empty\","),
                    "the empty unit's mappings belong to its own artifact");
            }
        }
        String emptySource = first.modules().stream()
            .filter(a -> a.moduleId().equals(new ModuleId("empty")))
            .findFirst().orElseThrow().source();
        check(emptySource.contains("$rt.initModule(\"empty\","),
            "an empty implementation unit still renders its own artifact and"
                + " private module record");
        check(!first.modules().stream()
                .anyMatch(a -> a.artifactRelativePath().contains("cfg")),
            "declaration units have no emission member");
        check(first.modules().stream()
                .anyMatch(a -> a.artifactRelativePath().startsWith("app")
                    && a.source().contains("require(\"./util\")")),
            "the compiled import requires the sibling artifact with the"
                + " retained relative specifier");
        check(first.modules().stream()
                .filter(a -> a.artifactRelativePath().equals("app.js"))
                .findFirst().orElseThrow().source()
                .contains("$require(\"./deal/runtime\")"),
            "the runtime require keeps the retained relative specifier");
    }

    private static void testSourceMapCoordinates(Path tmpRoot) throws Exception {
        System.out.println("-- the effective map list is coordinate-only and"
            + " ordered, and a comment precedes each mapped group --");
        Path root = Files.createTempDirectory(tmpRoot, "maps");
        Fixture fixture = compileFixture(root, """
            export function main(): null {
              let a: int = 1;
              let b: int = a + 1;
              if (b !== 2) { throw { code: "TEST_FAIL", message: "sum" }; }
              return null;
            }
            """);
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(true));
        JsModuleArtifact app = emission.modules().get(0);
        check(app.mappings() != null && !app.mappings().isEmpty(),
            "the effective map selection produces an ordered mapping list");
        List<SourceMapGenerator.Mapping> mappings = app.mappings();
        for (int i = 1; i < mappings.size(); i++) {
            SourceMapGenerator.Mapping previous = mappings.get(i - 1);
            SourceMapGenerator.Mapping current = mappings.get(i);
            check(current.generatedLine() > previous.generatedLine()
                    || (current.generatedLine() == previous.generatedLine()
                        && current.generatedColumn()
                            >= previous.generatedColumn()),
                "the recorded coordinate list is ordered by generated position");
        }
        String[] lines = app.source().split("\n", -1);
        for (SourceMapGenerator.Mapping mapping : mappings) {
            String line = lines[mapping.generatedLine() - 1];
            check(line.trim().startsWith("//"),
                "the mapped generated position is the source-comment line"
                    + " (" + mapping.generatedLine() + ": "
                    + line.trim() + ")");
            check(line.contains(":" + mapping.sourceLine() + ":"
                    + mapping.sourceColumn()),
                "the comment carries the same source position as the mapping"
                    + " (" + line.trim() + ")");
        }
        check(app.mappings().stream().anyMatch(m -> m.sourceLine() == 3),
            "an emitted statement group maps to its own source line");
    }

    // =========================================================================
    // 1b. Version-1 per-module sidecars from the recorded coordinates
    // =========================================================================

    /**
     * One strict version-1 sidecar: the parsed top-level keys in their
     * recorded order, the {@code source}/{@code generated} display strings
     * and the ordered mapping tuples. Parsed by the strict reader below, so
     * an extra key, a missing key, a non-integer coordinate or a differently
     * ordered entry fails rather than passing a substring probe.
     */
    private record Sidecar(String version, String source, String generated,
                           List<int[]> mappings) {
    }

    /**
     * ISSUE-0802: the recorded coordinates of every artifact are exactly the
     * per-module version-1 sidecar set the production owner will stage —
     * one {@code <module>.deal.map.json} per implementation artifact, its own
     * {@code SourceMapPaths} source and its own artifact as {@code generated},
     * coordinate-only mappings, single-source bounds and a supplementary
     * Unicode path roundtrip; the internal map-off rendering carries no
     * comment and no sidecar mapping while keeping its runtime metadata; and
     * an independent rebuild of the same sources in another root reproduces
     * byte-identical sidecars (no nonce, temp or run data).
     */
    private static void testSourceMapSidecars(Path tmpRoot) throws Exception {
        System.out.println("-- version-1 per-module sidecars: exact keys,"
            + " own source/artifact paths, single-source bounds, empty units,"
            + " supplementary Unicode and an independent byte-identical"
            + " rebuild --");
        String supplementary = "\uD83D\uDE00";
        Path root = Files.createTempDirectory(tmpRoot, "sidecars-" + supplementary);
        Path sourceDir = root.resolve(supplementary + "src");
        String appSource = """
            import * as util from "./util";
            import * as empty from "./empty";

            export function main(): null {
              let total: int = 0;
              for (let i: int = 0; i < 3; i = i + 1) { total = total + i; }
              let y: int = util.addOne(total);
              if (y !== 4) { throw { code: "TEST_FAIL", message: "cross" }; }
              while (total > 0) { total = total - 1; }
              return null;
            }
            """;
        Map<String, String> extraSources = Map.of(
            supplementary + "src/util.deal", """
                export function addOne(x: int): int {
                  return x + 1;
                }
                """,
            supplementary + "src/empty.deal", """
                // an implementation unit with no statements
                """);
        Fixture fixture = compileFixtureAt(root, sourceDir, appSource,
            extraSources);
        Path liveRoot = root.resolve("build" + supplementary + "/js");
        JsProjectEmission emission = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(true, liveRoot));
        String projectRootDisplay = liveRoot.toAbsolutePath().normalize()
            .resolve("..").resolve("..").normalize()
            .relativize(sourceDir.resolve("app.deal").toAbsolutePath())
            .toString();
        check(projectRootDisplay.startsWith(supplementary),
            "the fixture's source directory carries a supplementary character:"
                + " " + projectRootDisplay);

        // Exactly one sidecar per implementation artifact, at the module's own
        // relative path, and every artifact's source is declared once.
        List<String> wholeClosureSidecars = new ArrayList<>();
        for (JsModuleArtifact artifact : emission.modules()) {
            wholeClosureSidecars.add(artifact.moduleId().path()
                .replace('.', '/') + ".deal.map.json");
        }
        checkEq(emission.modules().stream().map(
                JsSemanticEmitterTest::sidecarName).toList(),
            wholeClosureSidecars,
            "the closure declares one sidecar name per artifact, at the"
                + " module's own relative path");

        verifyEmissionSidecars(fixture, emission, liveRoot, root);

        // The empty implementation unit keeps its own sidecar and its own
        // coordinates; a module that recorded no mappable position still
        // serializes the pinned empty array (the owner's branch).
        JsModuleArtifact emptyArtifact = emission.modules().stream()
            .filter(a -> a.moduleId().equals(new ModuleId("empty")))
            .findFirst().orElseThrow();
        check(emptyArtifact.mappings() != null,
            "the empty implementation unit carries an effective map list");
        check(parseSidecarStrict(sidecarName(emptyArtifact),
                sidecarJson(fixture, emptyArtifact, liveRoot)).source()
                .equals(SourceMapPaths.sourceDisplay(
                    fixture.sourceId(new ModuleId("empty")), liveRoot)),
            "the empty implementation unit's sidecar names its own source");
        Sidecar emptyList = parseSidecarStrict("empty-map",
            new SourceMapGenerator().toJson("src/empty.deal",
                "build/js/empty.js"));
        checkEq(0, emptyList.mappings().size(),
            "a module with no recorded position serializes the pinned empty"
                + " mappings array");
        check(!new SourceMapGenerator().toJson("src/empty.deal",
                "build/js/empty.js").contains("\"version\": 2"),
            "the sidecar dialect is version 1 only");

        // The supplementary Unicode path roundtrips through the sidecar text
        // and the emitted source comment (a surrogate pair stays intact).
        Sidecar supplementarySidecar = parseSidecarStrict("supplementary",
            sidecarJson(fixture, emission.modules().stream()
                .filter(a -> a.moduleId().equals(new ModuleId("app")))
                .findFirst().orElseThrow(), liveRoot));
        check(supplementarySidecar.source().contains(supplementary),
            "the sidecar source string roundtrips the supplementary"
                + " character: " + supplementarySidecar.source());
        check(supplementarySidecar.generated().contains(supplementary),
            "the sidecar generated string roundtrips the supplementary"
                + " character: " + supplementarySidecar.generated());
        check(supplementarySidecar.source().codePointCount(0,
                supplementarySidecar.source().length())
                == supplementarySidecar.source().length() - 1,
            "the supplementary character stays one code point (one surrogate"
                + " pair): " + supplementarySidecar.source());

        // A group whose recorded origin is not this module's own source and
        // that no recorded parent links stays unmapped: no coordinate is
        // invented, and the artifact's mapping list is the pinned empty one
        // (its sidecar still exists).
        JsEmissionInput canonical = fixture.emissionInput(true, liveRoot);
        Map<ModuleId, JsSourceFile> elsewhere = new LinkedHashMap<>();
        for (Map.Entry<ModuleId, JsSourceFile> entry
                : canonical.sourceFiles().entrySet()) {
            elsewhere.put(entry.getKey(), new JsSourceFile(
                "elsewhere/" + entry.getKey().path() + ".deal",
                entry.getValue().displayName()));
        }
        JsProjectEmission unlocated = JsSemanticEmitter.emitProductionProject(
            new JsEmissionInput(canonical.project(), canonical.tables(),
                canonical.registries(), canonical.seeds(),
                canonical.namespaces(), canonical.declarations(), elsewhere,
                true));
        for (JsModuleArtifact artifact : unlocated.modules()) {
            check(artifact.mappings() != null,
                "an artifact with no located group still carries an ordered"
                    + " list (" + artifact.artifactRelativePath() + ")");
            check(artifact.mappings().isEmpty(),
                "a group whose origin is not its module's own source and that"
                    + " no recorded parent links invents no coordinate ("
                    + artifact.artifactRelativePath() + ")");
            check(!sourceCommentLines(artifact.source()).findAny()
                    .isPresent(), "such an artifact opens no source comment ("
                    + artifact.artifactRelativePath() + ")");
            checkEq(0, parseSidecarStrict(sidecarName(artifact),
                    sidecarJson(fixture, artifact, liveRoot)).mappings().size(),
                sidecarName(artifact) + " carries the empty mappings array");
        }

        // The internal map-off rendering: no list, no comments, unchanged
        // runtime metadata.
        JsProjectEmission unmapped = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(false, liveRoot));
        for (JsModuleArtifact artifact : unmapped.modules()) {
            check(artifact.mappings() == null,
                "the internal map-off rendering records no mapping list ("
                    + artifact.artifactRelativePath() + ")");
            check(!sourceCommentLines(artifact.source()).findAny().isPresent(),
                "the internal map-off rendering emits no source comment ("
                    + artifact.artifactRelativePath() + ")");
        }
        JsModuleArtifact unmappedApp = unmapped.modules().stream()
            .filter(a -> a.moduleId().equals(new ModuleId("app")))
            .findFirst().orElseThrow();
        check(unmappedApp.source().contains(
                fixture.sourceId(new ModuleId("app"))),
            "the map-off artifact still embeds its runtime source metadata");
        // Mapping adds comments and nothing else: removing exactly the
        // source-comment lines reproduces the map-off bytes, so no generated
        // statement, effect or coordinate was relocated.
        for (int i = 0; i < emission.modules().size(); i++) {
            JsModuleArtifact mappedArtifact = emission.modules().get(i);
            JsModuleArtifact plainArtifact = unmapped.modules().get(i);
            String stripped = mappedArtifact.source().lines()
                .filter(line -> sourceCommentLines(line).findAny().isEmpty())
                .reduce(new StringBuilder(), (text, line) -> text.append(line)
                    .append('\n'), StringBuilder::append).toString();
            checkEq(plainArtifact.source(), stripped,
                mappedArtifact.artifactRelativePath() + " differs from its"
                    + " map-off bytes only by the mapped source comments");
        }

        // An independent rebuild in another root reproduces byte-identical
        // sidecars: the coordinates and the projected path strings carry no
        // nonce, temp or run data.
        Path secondRoot = Files.createTempDirectory(tmpRoot,
            "sidecars-rebuild-" + supplementary);
        Path secondSourceDir = secondRoot.resolve(supplementary + "src");
        Fixture second = compileFixtureAt(secondRoot, secondSourceDir,
            appSource, extraSources);
        Path secondLiveRoot = secondRoot.resolve("build" + supplementary
            + "/js");
        JsProjectEmission rebuilt = JsSemanticEmitter.emitProductionProject(
            second.emissionInput(true, secondLiveRoot));
        checkEq(emission.modules().size(), rebuilt.modules().size(),
            "the independent rebuild renders the same artifact set");
        for (int i = 0; i < emission.modules().size(); i++) {
            JsModuleArtifact first = emission.modules().get(i);
            JsModuleArtifact other = rebuilt.modules().get(i);
            checkEq(sidecarJson(fixture, first, liveRoot),
                sidecarJson(second, other, secondLiveRoot),
                first.artifactRelativePath() + " sidecar is byte-identical"
                    + " across independent roots");
            checkEq(first.mappings(), other.mappings(),
                first.artifactRelativePath() + " records the same coordinates"
                    + " across independent roots");
        }
    }

    /**
     * ISSUE-0802: the local/foreign mapping split across a real module edge.
     * The caller artifact carries the provider-annotated parameter boundary
     * at its execution position, its physical group maps to the local
     * initiating call (never to the provider coordinates under the caller
     * source), the provider's own sidecar keeps the provider's checks, and a
     * real Node invocation keeps the authoritative provider tuple with one
     * check and one retained caller effect per attempt.
     */
    private static void testSourceMapForeignPlacement(Path tmpRoot,
                                                      boolean node)
            throws Exception {
        System.out.println("-- foreign-origin boundary children stay at their"
            + " execution position and map through the local initiating call;"
            + " provider checks stay in the provider sidecar --");
        Path root = Files.createTempDirectory(tmpRoot, "maps-foreign");
        StringBuilder provider = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            provider.append("// provider filler line ").append(i).append('\n');
        }
        // The declared parameter sits beyond the caller's own source bounds,
        // so a leaked provider coordinate would also fail the bound check.
        provider.append("export function need(n: int): int {\n");
        provider.append("  let seen: int = 0;\n");
        provider.append("  return n + seen;\n");
        provider.append("}\n");
        List<String> providerLines = provider.toString().lines().toList();
        int providerParamLine = 0;
        int providerReturnLine = 0;
        for (int i = 0; i < providerLines.size(); i++) {
            if (providerLines.get(i).startsWith("export function need(")) {
                providerParamLine = i + 1;
            }
            if (providerLines.get(i).contains("return n + seen;")) {
                providerReturnLine = i + 1;
            }
        }
        // The recorded parameter annotation is the declared type's own span.
        int providerParamColumn = providerParamLine == 0 ? 0
            : providerLines.get(providerParamLine - 1).indexOf("int") + 1;
        check(providerParamLine > 0 && providerReturnLine > 0
                && providerParamColumn > 0,
            "the provider fixture declares its boundary annotation");
        Fixture fixture = compileFixture(root, """
            import * as lib from "./lib";

            export class Counter {
              n: int = 0;
            }

            export function attempt(c: Counter, t: table): int {
              c.n = c.n + 1;
              return lib.need(t.v);
            }

            export function main(): null { return null; }
            """, Map.of("src/lib.deal", provider.toString()));
        Path liveRoot = root.resolve("build/js");
        JsProjectEmission emission = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(true, liveRoot));
        JsModuleArtifact app = artifactOf(emission, "app");
        JsModuleArtifact lib = artifactOf(emission, "lib");
        String appDisplay = SourceMapPaths.sourceDisplay(
            fixture.sourceId(app.moduleId()), liveRoot);
        String libDisplay = SourceMapPaths.sourceDisplay(
            fixture.sourceId(lib.moduleId()), liveRoot);
        List<String> appLines = app.source().lines().toList();
        List<String> appSource = Files.readAllLines(
            fixture.sourcePath(app.moduleId()), StandardCharsets.UTF_8);
        List<String> libSource = Files.readAllLines(
            fixture.sourcePath(lib.moduleId()), StandardCharsets.UTF_8);
        String providerSource = fixture.sourceId(lib.moduleId());

        // The parameter boundary keeps the provider's own tuple at its
        // execution position inside the caller artifact, exactly once.
        // The emitted runtime arguments carry the provider's source id as a
        // JS string literal, then the annotation's line and column.
        String providerTuple = "\"" + providerSource + "\", "
            + providerParamLine + ", " + providerParamColumn;
        checkEq(1, countOccurrences(app.source(), providerTuple),
            "the cross-module parameter boundary appears once in the caller"
                + " artifact with the provider's authoritative tuple");
        check(appSource.size() < providerParamLine,
            "the provider annotation lies beyond the caller's own source"
                + " bounds, so a leaked provider coordinate is detectable");

        // Its physical group maps to the local initiating call.
        int callLine = 0;
        int callColumn = 0;
        for (int i = 0; i < appSource.size(); i++) {
            int at = appSource.get(i).indexOf("lib.need(");
            if (at >= 0) {
                callLine = i + 1;
                callColumn = at + 1;
            }
        }
        check(callLine > 0 && callColumn > 0,
            "the caller fixture declares the cross-module call");
        int boundaryLine = -1;
        for (int i = 0; i < appLines.size(); i++) {
            if (appLines.get(i).contains(providerTuple)
                    && appLines.get(i).contains("$rt.checkType(\"int\"")) {
                boundaryLine = i + 1;
            }
        }
        check(boundaryLine > 0,
            "the provider-annotated boundary is emitted in the caller"
                + " artifact");
        int groupCommentLine = -1;
        Pattern localComment = Pattern.compile(
            "\\s*// " + Pattern.quote(appDisplay) + ":([0-9]+):([0-9]+)");
        for (int i = boundaryLine - 1; i >= 1 && boundaryLine > 0; i--) {
            if (localComment.matcher(appLines.get(i - 1)).matches()) {
                groupCommentLine = i;
                break;
            }
        }
        check(groupCommentLine > 0,
            "the boundary's physical group opens a source comment");
        if (groupCommentLine > 0) {
            checkEq("// " + appDisplay + ":" + callLine + ":" + callColumn,
                appLines.get(groupCommentLine - 1).strip(),
                "the foreign child's group maps to the local initiating call,"
                    + " never to the provider coordinates");
        }

        Sidecar appSidecar = parseSidecarStrict("app-sidecar",
            sidecarJson(fixture, app, liveRoot));
        boolean localMapping = false;
        boolean foreignCoordinate = false;
        for (int[] mapping : appSidecar.mappings()) {
            if (groupCommentLine > 0 && mapping[0] == groupCommentLine
                    && mapping[1] == appLines.get(groupCommentLine - 1)
                        .indexOf("// ") + 1
                    && mapping[2] == callLine && mapping[3] == callColumn) {
                localMapping = true;
            }
            if (mapping[2] == providerParamLine
                    && mapping[3] == providerParamColumn) {
                foreignCoordinate = true;
            }
            check(mapping[2] <= appSource.size(),
                "the caller sidecar keeps every source coordinate inside the"
                    + " caller's own source file: " + mapping[2]);
        }
        check(localMapping,
            "the caller sidecar maps the boundary group to the local call");
        check(!foreignCoordinate,
            "the caller sidecar never carries the provider coordinates under"
                + " the caller source");

        // The provider's own check groups stay in the provider sidecar at the
        // provider's annotations.
        Sidecar libSidecar = parseSidecarStrict("lib-sidecar",
            sidecarJson(fixture, lib, liveRoot));
        int expectedReturnLine = providerReturnLine;
        boolean providerReturnMapped = libSidecar.mappings().stream()
            .anyMatch(mapping -> mapping[2] == expectedReturnLine);
        check(providerReturnMapped,
            "the provider sidecar maps the provider's own return boundary to"
                + " its annotation");
        for (int[] mapping : libSidecar.mappings()) {
            check(mapping[2] <= libSource.size(),
                "the provider sidecar keeps every source coordinate inside"
                    + " the provider's own source file: " + mapping[2]);
        }
        check(lib.source().contains(libDisplay + ":" + providerReturnLine + ":"),
            "the provider artifact's own comment spells the provider"
                + " annotation");

        if (!node) {
            skip("the foreign-placement drive needs the real Node binary");
            return;
        }
        Path out = stage(fixture, "maps-foreign", true);
        Files.writeString(out.resolve("driver.js"), """
            const app = require("./app.js");
            const counter = app.Counter$new({}, "d.js", 1, 1);
            const out = {before: counter.n, errors: []};
            for (let i = 0; i < 2; i++) {
              try {
                app.attempt.$f(counter, new Map([["v", 1.5]]));
              } catch (e) {
                out.errors.push({code: e.code, message: e.message,
                  file: e.file, line: e.line, column: e.column});
              }
            }
            out.after = counter.n;
            console.log(JSON.stringify(out));
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the foreign-placement driver exits 0: "
            + run.output());
        check(run.output().contains("\"before\":0"),
            "the caller's effect starts unexecuted: " + run.output());
        String expectedTuple = "\"file\":\"" + providerSource.replace("\\", "\\\\")
            + "\",\"line\":" + providerParamLine + ",\"column\":"
            + providerParamColumn;
        checkEq(2, countOccurrences(run.output(), "\"code\":\"E8001\""),
            "each attempt reports exactly one DEAL failure: " + run.output());
        checkEq(2, countOccurrences(run.output(), expectedTuple),
            "each failure keeps the authoritative provider tuple: "
                + run.output());
        check(run.output().contains("expected int"),
            "the provider boundary family keeps its expected token: "
                + run.output());
        check(run.output().contains("\"after\":2"),
            "the caller's effect ran exactly once per attempt (no"
                + " duplicated check or effect): " + run.output());
    }

    /** One artifact of an emission by module path, or a failure. */
    private static JsModuleArtifact artifactOf(JsProjectEmission emission,
                                               String modulePath) {
        return emission.modules().stream()
            .filter(artifact -> artifact.moduleId()
                .equals(new ModuleId(modulePath)))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "no artifact for module '" + modulePath + "'"));
    }

    /**
     * Verifies one emission's complete sidecar set against its own artifacts
     * and source files (the reused acceptance of every map drive): exactly
     * one strict version-1 {@code <module>.deal.map.json} per implementation
     * artifact, the owner's {@link SourceMapPaths} projection of that
     * module's own source and artifact, the recorded coordinate list in
     * order, one source comment per mapping with the same generated
     * line/column, and every source coordinate inside the module's own
     * source file.
     */
    private static void verifyEmissionSidecars(Fixture fixture,
            JsProjectEmission emission, Path liveRoot, Path projectRoot)
            throws IOException {
        List<String> sidecars = new ArrayList<>();
        for (JsModuleArtifact artifact : emission.modules()) {
            sidecars.add(sidecarName(artifact));
        }
        checkEq(emission.modules().size(), sidecars.size(),
            "every implementation artifact declares exactly one sidecar");
        checkEq(sidecars.size(), (int) sidecars.stream().distinct().count(),
            "no two artifacts share one sidecar path: " + sidecars);
        for (JsModuleArtifact artifact : emission.modules()) {
            String name = sidecarName(artifact);
            String json = sidecarJson(fixture, artifact, liveRoot);
            Sidecar sidecar = parseSidecarStrict(name, json);
            checkEq("1", sidecar.version(),
                name + " carries the pinned version");
            String expectedSource = SourceMapPaths.sourceDisplay(
                fixture.sourceId(artifact.moduleId()), liveRoot);
            checkEq(expectedSource, sidecar.source(),
                name + " names its own project-relative source");
            String expectedGenerated = SourceMapPaths.generatedDisplay(liveRoot,
                liveRoot.resolve(artifact.artifactRelativePath()));
            checkEq(expectedGenerated, sidecar.generated(),
                name + " names that module's own artifact path");
            checkEq(artifact.artifactRelativePath().substring(
                    artifact.artifactRelativePath().lastIndexOf('/') + 1),
                sidecar.generated().substring(
                    sidecar.generated().lastIndexOf('/') + 1),
                name + " maps the generated artifact of the same module");
            check(!sidecar.source().contains(projectRoot.toString())
                    && !sidecar.generated().contains(projectRoot.toString()),
                name + " carries no absolute run path: " + json);
            check(!sidecar.source().contains("deal-stage-")
                    && !sidecar.generated().contains("deal-stage-")
                    && !sidecar.source().contains("deal-publish")
                    && !sidecar.generated().contains("deal-publish"),
                name + " carries no stage nonce: " + json);

            // The recorded tuples are exactly the artifact's coordinate list.
            List<int[]> recorded = new ArrayList<>();
            for (SourceMapGenerator.Mapping mapping : artifact.mappings()) {
                recorded.add(new int[] {mapping.generatedLine(),
                    mapping.generatedColumn(), mapping.sourceLine(),
                    mapping.sourceColumn()});
            }
            checkEq(recorded.size(), sidecar.mappings().size(),
                name + " carries every recorded coordinate");
            boolean sameTuples = true;
            for (int i = 0; i < Math.min(recorded.size(),
                    sidecar.mappings().size()); i++) {
                sameTuples &= java.util.Arrays.equals(recorded.get(i),
                    sidecar.mappings().get(i));
            }
            check(sameTuples, name + " carries the recorded tuples in order");

            // The mapped comment and the recorded mapping are one set: every
            // source-comment line of the artifact is a mapping, in order, so
            // no group is commented without a mapping and no mapping is
            // recorded without its comment.
            List<String> artifactText = artifact.source().lines().toList();
            List<Integer> commentLines = new ArrayList<>();
            for (int i = 0; i < artifactText.size(); i++) {
                if (sourceCommentLines(artifactText.get(i)).findAny()
                        .isPresent()) {
                    commentLines.add(i + 1);
                }
            }
            checkEq(artifact.mappings().size(), commentLines.size(),
                name + " records exactly one mapping per source comment");
            for (int i = 0; i < Math.min(commentLines.size(),
                    artifact.mappings().size()); i++) {
                SourceMapGenerator.Mapping mapping = artifact.mappings().get(i);
                String comment = artifactText.get(commentLines.get(i) - 1);
                checkEq("// " + expectedSource + ":" + mapping.sourceLine()
                        + ":" + mapping.sourceColumn(),
                    comment.strip(),
                    name + " maps the " + i + "th source comment");
                checkEq(mapping.generatedLine(), commentLines.get(i),
                    name + " records the comment's own generated line");
                checkEq(comment.indexOf("// ") + 1, mapping.generatedColumn(),
                    name + " records the comment's own generated column");
            }

            // Single-source bounds: every mapping lies inside this module's
            // own source file and its own artifact, and the mapped generated
            // line is exactly the source comment carrying those coordinates.
            List<String> sourceLines = Files.readAllLines(
                fixture.sourcePath(artifact.moduleId()),
                StandardCharsets.UTF_8);
            for (int[] mapping : sidecar.mappings()) {
                check(mapping[0] >= 1 && mapping[0] <= artifactText.size(),
                    name + " maps a real generated line: " + mapping[0]);
                check(mapping[2] >= 1 && mapping[2] <= sourceLines.size(),
                    name + " maps a real source line of its own file: "
                        + mapping[2] + " of " + sourceLines.size());
                if (mapping[0] >= 1 && mapping[0] <= artifactText.size()) {
                    String generatedLine = artifactText.get(mapping[0] - 1);
                    check(generatedLine.contains(expectedSource + ":"
                            + mapping[2] + ":" + mapping[3]),
                        name + " maps the comment carrying its source"
                            + " position: " + generatedLine.strip());
                }
                if (mapping[2] >= 1 && mapping[2] <= sourceLines.size()) {
                    check(mapping[3] >= 1
                            && mapping[3] <= sourceLines.get(mapping[2] - 1)
                                .length() + 1,
                        name + " maps a real source column: " + mapping[3]);
                }
            }
        }
    }

    /** One artifact's version-1 sidecar path: {@code <module>.deal.map.json}. */
    private static String sidecarName(JsModuleArtifact artifact) {
        return artifact.moduleId().path().replace('.', '/')
            + ".deal.map.json";
    }

    /**
     * One artifact's sidecar JSON exactly as the production owner assembles
     * it: the artifact's coordinate list recorded into the shared
     * {@link SourceMapGenerator} and serialized with the owner's
     * {@link SourceMapPaths} live-root projection — the module's own source
     * file as {@code source}, the module's own artifact as {@code generated}.
     */
    private static String sidecarJson(Fixture fixture, JsModuleArtifact artifact,
                                      Path liveOutputRoot) {
        SourceMapGenerator generator = new SourceMapGenerator();
        if (artifact.mappings() != null) {
            for (SourceMapGenerator.Mapping mapping : artifact.mappings()) {
                generator.emitStatement(mapping.generatedLine(),
                    mapping.generatedColumn(), mapping.sourceLine(),
                    mapping.sourceColumn());
            }
        }
        Path generated = liveOutputRoot.resolve(artifact.artifactRelativePath());
        String[] paths = SourceMapPaths.projectRelative(
            fixture.sourceId(artifact.moduleId()), liveOutputRoot, generated);
        return generator.toJson(paths[0], paths[1]);
    }

    /** The source-comment lines of one emitted artifact. */
    private static Stream<String> sourceCommentLines(String source) {
        Pattern marker = Pattern.compile(
            "^\\s*// [^\\s]+:[0-9]+:[0-9]+$");
        return source.lines().filter(line -> marker.matcher(line).matches());
    }

    /**
     * The strict version-1 reader: the top-level object carries exactly the
     * four pinned keys in the pinned order, {@code version} is the number 1,
     * {@code source}/{@code generated} are strings, and every mapping entry
     * carries exactly the four pinned integer keys in the pinned order. Any
     * other key, order, type or trailing text fails.
     */
    private static Sidecar parseSidecarStrict(String name, String json) {
        Cursor cursor = new Cursor(json);
        cursor.objectStart();
        String versionKey = cursor.string();
        cursor.keySeparator();
        String version = cursor.number();
        cursor.valueSeparator();
        String sourceKey = cursor.string();
        cursor.keySeparator();
        String source = cursor.string();
        cursor.valueSeparator();
        String generatedKey = cursor.string();
        cursor.keySeparator();
        String generated = cursor.string();
        cursor.valueSeparator();
        String mappingsKey = cursor.string();
        cursor.keySeparator();
        cursor.arrayStart();
        List<int[]> mappings = new ArrayList<>();
        List<List<String>> mappingKeys = new ArrayList<>();
        while (cursor.hasMoreArrayItems()) {
            cursor.objectStart();
            List<String> keys = new ArrayList<>();
            int[] tuple = new int[4];
            for (int i = 0; i < 4; i++) {
                if (i > 0) {
                    cursor.valueSeparator();
                }
                keys.add(cursor.string());
                cursor.keySeparator();
                tuple[i] = Integer.parseInt(cursor.number());
            }
            checkEq(4, keys.size(),
                name + " spells exactly the four mapping keys");
            mappingKeys.add(List.copyOf(keys));
            cursor.objectEnd();
            mappings.add(tuple);
        }
        cursor.arrayEnd();
        cursor.objectEnd();
        cursor.end();
        checkEq("version", versionKey, name + " spells the version key");
        checkEq("source", sourceKey, name + " spells the source key");
        checkEq("generated", generatedKey, name + " spells the generated key");
        checkEq("mappings", mappingsKey, name + " spells the mappings key");
        for (List<String> keys : mappingKeys) {
            checkEq(List.of("generatedLine", "generatedColumn", "sourceLine",
                    "sourceColumn"), keys,
                name + " spells the mapping keys in the pinned order");
        }
        return new Sidecar(version, source, generated, mappings);
    }

    /** A minimal strict JSON cursor over the pinned sidecar shape. */
    private static final class Cursor {
        private final String text;
        private int at;

        Cursor(String text) {
            this.text = text;
        }

        void objectStart() {
            expect('{');
            skipWhitespace();
        }

        void objectEnd() {
            expect('}');
            skipWhitespace();
        }

        void arrayStart() {
            expect('[');
            skipWhitespace();
        }

        void arrayEnd() {
            expect(']');
            skipWhitespace();
        }

        boolean hasMoreArrayItems() {
            skipWhitespace();
            if (at < text.length() && text.charAt(at) == ']') {
                return false;
            }
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
                skipWhitespace();
            }
            return at < text.length() && text.charAt(at) != ']';
        }

        void keySeparator() {
            expect(':');
            skipWhitespace();
        }

        void valueSeparator() {
            expect(',');
            skipWhitespace();
        }

        String string() {
            if (at >= text.length() || text.charAt(at) != '"') {
                throw new IllegalStateException("expected a JSON string at "
                    + at + " in: " + text);
            }
            at++;
            StringBuilder value = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    skipWhitespace();
                    return value.toString();
                }
                if (c == '\\') {
                    char escape = text.charAt(at++);
                    switch (escape) {
                        case '"' -> value.append('"');
                        case '\\' -> value.append('\\');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> {
                            value.append((char) Integer.parseInt(
                                text.substring(at, at + 4), 16));
                            at += 4;
                        }
                        default -> throw new IllegalStateException(
                            "unsupported escape \\" + escape);
                    }
                    continue;
                }
                value.append(c);
            }
            throw new IllegalStateException("unterminated JSON string");
        }

        String number() {
            int start = at;
            while (at < text.length()
                    && "-0123456789".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            if (start == at) {
                throw new IllegalStateException("expected a JSON number at "
                    + at + " in: " + text);
            }
            String number = text.substring(start, at);
            skipWhitespace();
            return number;
        }

        void end() {
            skipWhitespace();
            if (at != text.length()) {
                throw new IllegalStateException("trailing sidecar text at "
                    + at + " in: " + text);
            }
        }

        private void expect(char c) {
            skipWhitespace();
            if (at >= text.length() || text.charAt(at) != c) {
                throw new IllegalStateException("expected '" + c + "' at "
                    + at + " in: " + text);
            }
            at++;
            skipWhitespace();
        }

        private void skipWhitespace() {
            while (at < text.length()
                    && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }

    // =========================================================================
    // 2. Local values, containers and control flow under Node
    // =========================================================================

    private static void testValuesContainersAndControl(Path tmpRoot,
            boolean node) throws Exception {
        System.out.println("-- values, containers and control flow under the"
            + " real Node binary --");
        Path root = Files.createTempDirectory(tmpRoot, "values");
        Fixture fixture = compileFixture(root, """
            function classify(n: int): int {
              if (n < 0) { return 0 - 1; }
              while (n > 0) { n = n - 1; }
              return n;
            }

            export function main(): null {
              let ints: int[] = [1, 2, 3];
              let total: int = 0;
              for (let v: int of ints) { total = total + v; }
              if (total !== 6) { throw { code: "TEST_FAIL", message: "sum" }; }
              if (ints.length !== 3) { throw { code: "TEST_FAIL", message: "len" }; }
              let nums: number[] = [1.5, 2.5];
              let nsum: number = nums[0] + nums[1];
              if (nsum !== 4.0) { throw { code: "TEST_FAIL", message: "num" }; }
              let words: string[] = ["a", "b"];
              let joined: string = words[0] + words[1];
              if (joined !== "ab") { throw { code: "TEST_FAIL", message: "str" }; }
              let ordered: string = "n";
              if ("b" < "c") { ordered = "y"; }
              let buf: bytes = bytes(3);
              buf[0] = 7;
              let byte0: int = buf[0];
              if (byte0 !== 7) { throw { code: "TEST_FAIL", message: "byte" }; }
              if (buf.length !== 3) { throw { code: "TEST_FAIL", message: "blen" }; }
              let t: table = {a: 1, b: "x", __proto__: 1};
              t.__proto__ = 5;
              let proto: int = t.__proto__;
              if (proto !== 5) { throw { code: "TEST_FAIL", message: "proto" }; }
              t.c = 3;
              let c: int = t.c;
              if (c !== 3) { throw { code: "TEST_FAIL", message: "table" }; }
              delete t.b;
              let alias: table = t;
              alias.a = 7;
              let a: int = t.a;
              if (a !== 7) { throw { code: "TEST_FAIL", message: "alias" }; }
              let arrAlias: int[] = ints;
              arrAlias[0] = 9;
              if (ints[0] !== 9) { throw { code: "TEST_FAIL", message: "arr-alias" }; }
              if (classify(3) !== 0) { throw { code: "TEST_FAIL", message: "loop" }; }
              if (classify(0 - 2) !== 0 - 1) { throw { code: "TEST_FAIL", message: "guard" }; }
              if (ordered !== "y") { throw { code: "TEST_FAIL", message: "cmp" }; }
              let discarded: int = 0;
              discarded = 1;
              if (discarded !== 1) { throw { code: "TEST_FAIL", message: "seq" }; }
              return null;
            }
            """);
        Path out = stage(fixture, "values", false);
        String artifact = Files.readString(out.resolve("app.js"),
            StandardCharsets.UTF_8);
        check(artifact.contains("$rt.makeTable({[\"a\"]:"),
            "a table literal constructs the insertion-ordered Map carrier");
        check(artifact.contains(".set(\"c\",") && artifact.contains(".get(\"c\")"),
            "table writes and reads go through the Map carrier members");
        check(artifact.contains(".delete(\"b\")"),
            "a table delete removes the field (absence is not null)");
        check(artifact.contains("$rt.intAdd("),
            "recorded int arithmetic routes through the signed32 member");
        check(artifact.contains("($s") && artifact.contains(" + $s"),
            "recorded number arithmetic stays native IEEE");
        check(artifact.contains("$rt.bytes(3") || artifact.contains("$rt.bytes("),
            "the bytes allocation routes through the captured Uint8Array"
                + " carrier");
        check(artifact.contains("$rt.bytesGet(") && artifact.contains("$rt.bytesSet("),
            "bytes reads and writes route through the bounds-checked members");
        check(!artifact.contains("typeof"),
            "no emitted decision reads typeof: recorded facts select every"
                + " shape");
        if (!node) {
            skip("values/containers drive needs node");
            return;
        }
        NodeRun run = runNode(out, entryPath(out));
        checkEq(0, run.status(), "the values/containers program exits 0: "
            + run.output());
    }

    // =========================================================================
    // 2b. The nullable equality pair and the shared nil-equivalence rule
    // =========================================================================

    private static void testNullableEqualityNilEquivalence(Path tmpRoot,
            boolean node) throws Exception {
        System.out.println("-- the nullable equality pair applies the shared"
            + " nil-equivalence rule (null vs absent) --");
        Path root = Files.createTempDirectory(tmpRoot, "nullable-eq");
        Fixture fixture = compileFixture(root, """
            export function eq(a: int | null, b: int | null): boolean {
              return a === b;
            }

            export function ne(a: int | null, b: int | null): boolean {
              return a !== b;
            }

            export function main(): null {
              return null;
            }
            """);
        Path out = stage(fixture, "nullable-eq", false);
        String artifact = Files.readString(out.resolve("app.js"),
            StandardCharsets.UTF_8);
        // The equality arm composes strict identity with the shared
        // nil-equivalence clause — byte-identical to the inequality arm's
        // inner form and to the reference nil-aware emission.
        Pattern equalityForm = Pattern.compile(
            "\\$s\\d+ = \\(\\(\\$rt\\.numOf\\(\\$s\\d+\\) === "
                + "\\$rt\\.numOf\\(\\$s\\d+\\)\\) \\|\\| "
                + "\\(\\$rt\\.isNilEquivalent\\(\\$s\\d+\\) && "
                + "\\$rt\\.isNilEquivalent\\(\\$s\\d+\\)\\)\\);");
        check(equalityForm.matcher(artifact).find(),
            "the nullable equality arm carries the shared nil-equivalence"
                + " clause; the emitted arm was: " + equalityLineOf(artifact));
        if (!node) {
            skip("nullable nil-equivalence drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            const absent = undefined;
            console.log(JSON.stringify({
              eqAbsentNull: app.eq.$f(absent, null),
              neAbsentNull: app.ne.$f(absent, null),
              eqMissingNull: app.eq.$f($rt.MISSING, null),
              neMissingNull: app.ne.$f($rt.MISSING, null),
              eqMissingAbsent: app.eq.$f($rt.MISSING, absent),
              neMissingAbsent: app.ne.$f($rt.MISSING, absent),
              eqNullNull: app.eq.$f(null, null),
              eqSame: app.eq.$f(3, 3),
              neSame: app.ne.$f(3, 3),
              eqDifferent: app.eq.$f(3, 4),
              neDifferent: app.ne.$f(3, 4),
            }));
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the nullable-equality driver exits 0: "
            + run.output());
        check(run.output().contains("\"eqAbsentNull\":true"),
            "an absent operand compares equal to null: " + run.output());
        check(run.output().contains("\"neAbsentNull\":false"),
            "the inequality arm stays the complement: " + run.output());
        check(run.output().contains("\"eqMissingNull\":true"),
            "the absence sentinel compares equal to null: " + run.output());
        check(run.output().contains("\"neMissingNull\":false"),
            "the inequality arm stays the complement for the sentinel: "
                + run.output());
        check(run.output().contains("\"eqMissingAbsent\":true"),
            "two differently represented nil-equivalent operands compare"
                + " equal: " + run.output());
        check(run.output().contains("\"neMissingAbsent\":false"),
            "the inequality arm stays the complement for that pair: "
                + run.output());
        check(run.output().contains("\"eqNullNull\":true"),
            "null compares equal to null: " + run.output());
        check(run.output().contains("\"eqSame\":true"),
            "the inner int equality still applies: " + run.output());
        check(run.output().contains("\"neSame\":false"),
            "the inner int inequality stays the complement: "
                + run.output());
        check(run.output().contains("\"eqDifferent\":false"),
            "different non-nil operands compare unequal: " + run.output());
        check(run.output().contains("\"neDifferent\":true"),
            "different non-nil operands stay complementary: "
                + run.output());
    }

    /** The emitted equality-arm line for diagnostics, or null when absent. */
    private static String equalityLineOf(String artifact) {
        for (String line : artifact.split("\n", -1)) {
            if (line.contains("$rt.isNilEquivalent(")
                    && !line.contains("!((")) {
                return line.trim();
            }
        }
        return null;
    }

    private static void testCellsClosuresAndAdapters(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- live captures, fresh cells, recursion,"
            + " adapters and scalar iteration under Node --");
        Path root = Files.createTempDirectory(tmpRoot, "cells");
        Fixture fixture = compileFixture(root, """
            function even(n: int): boolean {
              if (n === 0) { return true; }
              return odd(n - 1);
            }

            function odd(n: int): boolean {
              if (n === 0) { return false; }
              return even(n - 1);
            }

            function compute(): int {
              let total: int = 0;
              for (let i: int = 0; i < 3; i = i + 1) {
                let f: () => int = function(): int { return i; };
                total = total + f();
              }
              let j: int = 0;
              let seen: int = 0;
              while (j < 2) {
                let k: int = j;
                let g: () => int = function(): int { return k; };
                seen = seen + g();
                j = j + 1;
              }
              return total + seen;
            }

            function loops(): int {
              let acc: int = 0;
              for (let i: int = 0; i < 10; i = i + 1) {
                if (i === 3) { continue; }
                if (i === 6) { break; }
                acc = acc + i;
              }
              if (acc !== 12) { throw { code: "TEST_FAIL", message: "fl" }; }
              let found: int = 0;
              for (let i: int = 0; i < 3; i = i + 1) {
                for (let j: int = 0; j < 3; j = j + 1) {
                  if (j === 1) { break; }
                  found = found + 1;
                }
              }
              return found;
            }

            function run(): int {
              let f: () => int = function(): int { return 7; };
              let g: (x: int) => int = f;
              f = function(): int { return 9; };
              let adapted: int = g(5);
              if (!even(4)) { throw { code: "TEST_FAIL", message: "even" }; }
              if (odd(4)) { throw { code: "TEST_FAIL", message: "odd" }; }
              return compute() + adapted + loops();
            }

            export function main(): null {
              let r: int = run();
              if (r !== 16) { throw { code: "TEST_FAIL", message: "cells" }; }
              let s: string = "a😀b";
              let count: int = 0;
              let collected: string = "";
              for (let ch: string of s) {
                count = count + 1;
                collected = collected + ch;
              }
              if (count !== 3) { throw { code: "TEST_FAIL", message: "scalars" }; }
              if (collected !== s) { throw { code: "TEST_FAIL", message: "rebuild" }; }
              return null;
            }
            """);
        Path out = stage(fixture, "cells", false);
        if (!node) {
            skip("cells/closures drive needs node");
            return;
        }
        NodeRun run = runNode(out, entryPath(out));
        checkEq(0, run.status(), "the cells/closures program exits 0: "
            + run.output());
    }

    // =========================================================================
    // 2c. The realized carriers: the seeded intrinsic identity, the array
    // hole decision, the adapter call origin, and the module-namespace
    // index carrier
    // =========================================================================

    /**
     * ISSUE-0798 review cycle 2, finding 1: a function-typed load of the
     * seeded intrinsic binding republishes the seeded identity, so the
     * seed's {@code BINDING_INIT} — and every other materialization of
     * the identity — must publish the one materialized carrier instead of
     * a slot that only exists inside another body. Reference identity
     * follows from the single cell the seed fills.
     */
    private static void testIntrinsicValueCarriers(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- the seeded intrinsic carrier: one materialized"
            + " value, identity-preserving loads read its cell --");
        Path root = Files.createTempDirectory(tmpRoot, "intrinsic");
        Fixture fixture = compileFixture(root, """
            export function storedInt(x: number): int {
              let f: (x: number) => int = int;
              return f(x);
            }

            export function same(): boolean {
              let f: (x: number) => int = int;
              let g: (x: number) => int = int;
              return f === g;
            }

            export function adapted(x: number, y: int): int {
              let g: (x: number, y: int) => int = int;
              return g(x, y);
            }

            export function main(): null {
              let a: int = storedInt(4.0);
              let b: boolean = same();
              let c: int = adapted(5.0, 0);
              if (a !== 4 || !b || c !== 5) {
                throw { code: "TEST_FAIL", message: "intrinsic" };
              }
              return null;
            }
            """);
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
            "the shared authority runs the intrinsic-value program: "
                + oracle.terminal());
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String artifact = emission.modules().get(0).source();
        checkEq(1, countOccurrences(artifact,
                "$rt.function(\"(number)->int\", (v, $f, $l, $c) =>"),
            "the module materializes exactly one carrier (the seed's own"
                + " BINDING_INIT); no site re-wraps the identity");
        check(Pattern.compile("\\$g\\d+ = \\$rt\\.function\\(\"\\(number\\)->int\","
                + " \\(v, \\$f, \\$l, \\$c\\) =>")
                .matcher(artifact).find(),
            "the seed binding's init publishes the carrier at the module"
                + " root");
        check(Pattern.compile("const \\$av\\d+ = \\$g\\d+;").matcher(artifact)
                .find(),
            "the adapter's VALUE operand reads the seed's binding cell"
                + " instead of emitting a fresh wrapper");
        Path out = stage(fixture, "intrinsic", emission);
        if (!node) {
            skip("the intrinsic value drive needs node");
            return;
        }
        NodeRun run = runNode(out, entryPath(out));
        checkEq(0, run.status(), "the intrinsic-value program loads and runs"
            + " (no undeclared seed slot): " + run.output());
    }

    /**
     * ISSUE-0798 review cycle 2, finding 2: the array read's element
     * boundary runs the bounds gate only (the shared
     * ARRAY_READ_INDEX_THEN_DESCRIPTOR policy), so an in-range missing
     * element flows to the contextual boundary — null for a nullable
     * descriptor, the E8001 arm at the contextual origin otherwise.
     */
    private static void testArrayHoleReadDecision(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- an in-range missing array element flows to"
            + " the contextual boundary --");
        Path root = Files.createTempDirectory(tmpRoot, "hole");
        Fixture nullable = compileFixture(root, """
            export function nullableHole(): int | null {
              let a: int[] = [1, 2, 3];
              delete a[1];
              let v: int | null = a[1];
              return v;
            }

            export function main(): null {
              if (nullableHole() !== null) {
                throw { code: "TEST_FAIL", message: "hole" };
              }
              return null;
            }
            """);
        var nullableOracle = deal.semantic.SemanticOracle.executeProjectInits(
            nullable.project(), nullable.tables(), nullable.registries(), null);
        check(nullableOracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
            "the shared authority reads the deleted element as null: "
                + nullableOracle.terminal());
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(
                nullable.emissionInput(false));
        String artifact = emission.modules().get(0).source();
        String[] lines = artifact.split("\n", -1);
        int read = arrayReadLine(lines);
        check(read >= 0 && read + 1 < lines.length
                && lines[read + 1].contains("checkType(\"?int\""),
            "the raw element passes straight to the contextual nullable"
                + " boundary with no strict element descriptor check in"
                + " between: " + (read >= 0 && read + 1 < lines.length
                    ? lines[read + 1].trim() : "no read line"));
        Path out = stage(nullable, "hole", emission);
        if (node) {
            NodeRun run = runNode(out, entryPath(out));
            checkEq(0, run.status(), "the nullable hole read returns null: "
                + run.output());
        } else {
            skip("the nullable hole drive needs node");
        }

        Fixture strict = compileFixture(root, """
            export function strictHole(): int {
              let a: int[] = [1, 2, 3];
              delete a[1];
              let v: int = a[1];
              return v;
            }

            export function main(): null {
              let x: int = strictHole();
              return null;
            }
            """);
        var strictOracle = deal.semantic.SemanticOracle.executeProjectInits(
            strict.project(), strict.tables(), strict.registries(), null);
        check(strictOracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure,
            "the shared authority rejects the missing element against the"
                + " contextual int descriptor: " + strictOracle.terminal());
        if (!(strictOracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure failure)) {
            return;
        }
        deal.semantic.SemanticRuntimeModel.ErrorSnapshot snapshot =
            failure.error();
        String strictArtifact = JsSemanticEmitter.emitProductionProject(
            strict.emissionInput(false)).modules().get(0).source();
        String[] strictLines = strictArtifact.split("\n", -1);
        int strictRead = arrayReadLine(strictLines);
        checkEq(true, strictRead >= 0 && strictRead + 1 < strictLines.length
                && strictLines[strictRead + 1].contains("checkType(\"int\"")
                && strictLines[strictRead + 1].contains(
                    originCoordinates(snapshot.origin()) + ")"),
            "the contextual check runs at the shared authority's own origin"
                + " (" + snapshot.origin() + "): "
                + (strictRead >= 0 && strictRead + 1 < strictLines.length
                    ? strictLines[strictRead + 1].trim() : "no read line"));
        Path strictOut = stage(strict, "hole-strict", false);
        if (!node) {
            skip("the strict hole drive needs node");
            return;
        }
        NodeRun strictRun = runNode(strictOut, entryPath(strictOut));
        checkEq(1, strictRun.status(), "the strict hole read exits 1");
        check(strictRun.output().contains("DEAL_ERROR_CODE: "
                + snapshot.code() + " " + snapshot.message() + " at "
                + snapshot.origin()),
            "the strict hole read raises the shared tuple at the"
                + " contextual origin: " + strictRun.output());
    }

    /**
     * ISSUE-0798 review cycle 2, finding 3: the emitted call appends the
     * call-site span triple and the adapter forwards the received triple
     * to its source wrapper, so a conversion failure behind an
     * arity-extension adapter keeps the authoritative call origin.
     */
    private static void testAdapterCallOrigin(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- the adapter forwards the call-site span to its"
            + " source wrapper --");
        Path root = Files.createTempDirectory(tmpRoot, "adapter-origin");
        Fixture fixture = compileFixture(root, """
            export function main(): null {
              let g: (x: number, y: int) => int = int;
              let n: number = 0.0 / 0.0;
              let r: int = g(n, 1);
              return null;
            }
            """);
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure,
            "the shared authority rejects the NaN through the adapter:"
                + " " + oracle.terminal());
        if (!(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure failure)) {
            return;
        }
        deal.semantic.SemanticRuntimeModel.ErrorSnapshot snapshot =
            failure.error();
        JsProjectEmission emission = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(false));
        String artifact = emission.modules().get(0).source();
        check(artifact.contains(
                "$rt.checkFunctionSig(\"(number)->int\", $src.$sig, $src,"
                    + " $file, $line, $column)"),
            "the adapter's source-signature check runs at the received call"
                + " span");
        check(artifact.contains(
                "return $src.$f($p0, $file, $line, $column);"),
            "the adapter forwards the received span triple to the source"
                + " wrapper");
        Path out = stage(fixture, "adapter-origin", emission);
        if (!node) {
            skip("the adapter origin drive needs node");
            return;
        }
        NodeRun run = runNode(out, entryPath(out));
        checkEq(1, run.status(), "the adapter-mediated conversion exits 1");
        check(run.output().contains("DEAL_ERROR_CODE: " + snapshot.code()
                + " " + snapshot.message() + " at " + snapshot.origin()),
            "the conversion failure behind the adapter keeps the"
                + " authoritative call origin: " + run.output());
    }

    /**
     * ISSUE-0798 review cycle 2, finding 4: a dynamic index operation on a
     * module-namespace value realizes the plain-object surface through the
     * carrier-neutral table members (own-property-safe), never a Map
     * member call on a plain object.
     */
    private static void testModuleNamespaceIndexCarrier(Path tmpRoot,
                                                        boolean node)
            throws Exception {
        System.out.println("-- the module-namespace index carrier is the"
            + " own-property-safe surface --");
        Path root = Files.createTempDirectory(tmpRoot, "namespace");
        Fixture fixture = compileFixture(root, """
            import * as util from "./utilw3";

            export function main(): null {
              let k: string = "counter";
              util[k] = 7;
              let v: int = util[k];
              if (v !== 7) { throw { code: "TEST_FAIL", message: "read" }; }
              let p: string = "__proto__";
              util[p] = 9;
              let w: int = util[p];
              if (w !== 9) { throw { code: "TEST_FAIL", message: "proto" }; }
              return null;
            }
            """, Map.of("src/utilw3.deal", """
                export function counter(): int {
                  return 0;
                }
                """));
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
            "the shared authority realizes the namespace index write/read:"
                + " " + oracle.terminal());
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String artifact = sourceOf(emission, "app");
        check(artifact.contains("$rt.tableSet(")
                && artifact.contains("$rt.tableGet("),
            "the namespace index arms use the carrier-neutral table members");
        check(!artifact.contains(".set(") && !artifact.contains(".get(")
                && !artifact.contains(".delete("),
            "no Map member call is emitted for the plain-object namespace"
                + " surface: " + artifact.lines()
                    .filter(l -> l.contains(".set(") || l.contains(".get("))
                    .findFirst().orElse(""));
        Path out = stage(fixture, "namespace", emission);
        if (!node) {
            skip("the namespace index drive needs node");
            return;
        }
        NodeRun run = runNode(out, entryPath(out));
        checkEq(0, run.status(), "the namespace index program exits 0: "
            + run.output());
        Files.writeString(out.resolve("driver.js"), """
            const app = require("./app.js");
            app.main.$f();
            const util = require("./utilw3.js");
            console.log(JSON.stringify({
              counter: util.counter,
              protoOwn: Object.prototype.hasOwnProperty.call(util, "__proto__"),
              protoValue: util["__proto__"],
              protoIntact: Object.getPrototypeOf(util) === Object.prototype}));
            """, StandardCharsets.UTF_8);
        NodeRun driver = runNode(out, "driver.js");
        checkEq(0, driver.status(), "the namespace driver exits 0: "
            + driver.output());
        check(driver.output().contains("\"counter\":7")
                && driver.output().contains("\"protoOwn\":true")
                && driver.output().contains("\"protoValue\":9")
                && driver.output().contains("\"protoIntact\":true"),
            "the dynamic writes reach the published surface as own"
                + " properties (__proto__ included) without moving the"
                + " prototype: " + driver.output());
    }

    /** The emitted raw array read line's index, or -1. */
    private static int arrayReadLine(String[] lines) {
        int read = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches(
                    "\\s*\\$s\\d+ = \\$rt\\.arrayAt\\(\\$s\\d+, "
                        + "\\$s\\d+, (true|false)\\);")) {
                read = i;
            }
        }
        return read;
    }

    /** The trailing {@code line, column} pair of one origin string. */
    private static String originCoordinates(String origin) {
        int last = origin.lastIndexOf(':');
        int previous = origin.lastIndexOf(':', last - 1);
        return origin.substring(previous + 1, last) + ", "
            + origin.substring(last + 1);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // =========================================================================
    // 3. Classes: defaults, construction, fields, JSON and the public surface
    // =========================================================================

    /**
     * The composed class drive (ISSUE-0799, ISSUE-0811 acceptance): one legal
     * module-class project exercised through real Node artifacts — fresh and
     * re-executed defaults, provided/optional skips, retained references,
     * imported provider construction, field read/write/delete/has, the public
     * helper ABI (the exact META/C$new/C$fromJson/C$toJson/C$fields keys and
     * signatures, C$new arity and origin absence, the untagged defaults-only
     * factory transfer with no default run at registration, unmutated provided
     * input and no instance on failure), the source-literal supplied-expression
     * order before omitted defaults, the raw host extra-key scan before any
     * default, the per-construction Error[] default, and the JSON roundtrip
     * with its specified null failures and optional-null three states. Both
     * the driver (require-only) and the entry artifact itself execute.
     */
    private static void testClassConstructionAndJson(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- classes under Node: per-attempt defaults, imported"
            + " providers, field commits, the public helper ABI and JSON --");
        Path root = Files.createTempDirectory(tmpRoot, "classes");
        Fixture fixture = compileFixture(root, """
            import * as lib from "./lib";

            class Bag {
              items: int[] = [];
              fresh: Item = makeItem();
              opt?: string;
            }

            class Item {
              v: int = 0;
            }

            function makeItem(): Item {
              return { v: 7 };
            }

            // A source literal's supplied expressions must run before any
            // omitted required default: the default's distinct failure would
            // otherwise replace the supplied expression's failure.
            function defaultBoom(): int {
              throw { code: "DEFAULT_ORDER", message: "default ran before supplied" };
            }

            class FailOrder {
              first: int = defaultBoom();
              second: int = 0;
            }

            // @jsonable
            export class Form {
              name: string = "";
              nick?: string;
              score: number = 0.0;
              point: lib.Point = {};
              many: lib.Point[] = [];
            }

            export class Fault {
              err: Error = {};
              errs: Error[] = [];
            }

            export function main(): null {
              let order: int[] = [];
              let note: (n: int) => int = function(n: int): int {
                order[order.length] = n;
                return n;
              };
              let suppliedBoom: () => int = function(): int {
                throw { code: "SUPPLIED_ORDER", message: "supplied expression first" };
              };
              let a: Bag = {};
              let b: Bag = {};
              a.items[0] = 5;
              if (b.items.length !== 0) { throw { code: "TEST_FAIL", message: "shared array default" }; }
              a.fresh.v = 1;
              if (b.fresh.v !== 7) { throw { code: "TEST_FAIL", message: "call default not re-executed" }; }
              if (a.fresh.v !== 1) { throw { code: "TEST_FAIL", message: "call default not retained" }; }
              let c: Bag = { items: [9], opt: "s" };
              if (c.items[0] !== 9) { throw { code: "TEST_FAIL", message: "provided skip" }; }
              if (!has(c.opt)) { throw { code: "TEST_FAIL", message: "provided optional" }; }
              delete c.opt;
              if (has(c.opt)) { throw { code: "TEST_FAIL", message: "deleted optional" }; }
              let p1: lib.Point = { x: 2 };
              let p2: lib.Point = {};
              if (p1.y !== 0 || p2.x !== 0) { throw { code: "TEST_FAIL", message: "provider defaults" }; }
              if (lib.Point$toJson(p1) !== "{\\"x\\":2,\\"y\\":0}") { throw { code: "TEST_FAIL", message: "provider toJson" }; }
              // A source literal evaluates its supplied expressions
              // left-to-right, then applies the omitted required defaults.
              let ordered: lib.Point = { x: note(1), opt: note(2) };
              if (order.length !== 2 || order[0] !== 1 || order[1] !== 2) { throw { code: "TEST_FAIL", message: "supplied order" }; }
              if (ordered.y !== 0) { throw { code: "TEST_FAIL", message: "default after supplied" }; }
              let orderCode: string = "";
              try {
                let failed: FailOrder = { second: suppliedBoom() };
                orderCode = "NOFAIL";
              } catch (e) {
                orderCode = e.code;
              }
              if (orderCode !== "SUPPLIED_ORDER") { throw { code: "TEST_FAIL", message: "supplied expression before default" }; }
              // The optional-nullable three states survive source literals and
              // the generated helpers: missing stays absent, a present null
              // stays present-null, a present value stays present.
              let pm: lib.Point = { x: 1 };
              let pn: lib.Point = { x: 1, opt: null };
              let pv: lib.Point = { x: 1, opt: 2 };
              if (has(pm.opt)) { throw { code: "TEST_FAIL", message: "missing optional" }; }
              if (!has(pn.opt)) { throw { code: "TEST_FAIL", message: "present null" }; }
              if (pn.opt !== null) { throw { code: "TEST_FAIL", message: "present null read" }; }
              if (!has(pv.opt)) { throw { code: "TEST_FAIL", message: "present value" }; }
              if (lib.Point$toJson(pm) !== "{\\"x\\":1,\\"y\\":0}") { throw { code: "TEST_FAIL", message: "missing optional toJson" }; }
              if (lib.Point$toJson(pn) !== "{\\"x\\":1,\\"y\\":0,\\"opt\\":null}") { throw { code: "TEST_FAIL", message: "present null toJson" }; }
              if (lib.Point$toJson(pv) !== "{\\"x\\":1,\\"y\\":0,\\"opt\\":2}") { throw { code: "TEST_FAIL", message: "present value toJson" }; }
              let dm: lib.Point | null = lib.Point$fromJson("{\\"x\\":1}");
              let dn: lib.Point | null = lib.Point$fromJson("{\\"x\\":1,\\"opt\\":null}");
              if (dm !== null) {
                if (has(dm.opt)) { throw { code: "TEST_FAIL", message: "decoded missing optional" }; }
              }
              if (dn !== null) {
                if (!has(dn.opt)) { throw { code: "TEST_FAIL", message: "decoded present null" }; }
              }
              let f: Form = { name: "Ada", point: { x: 3 }, many: [{ y: 4 }] };
              let s: string = Form$toJson(f);
              let g: Form | null = Form$fromJson(s);
              if (g !== null) {
                if (g.point.x !== 3) { throw { code: "TEST_FAIL", message: "nested identity" }; }
                if (g.many.length !== 1) { throw { code: "TEST_FAIL", message: "nested array" }; }
                if (has(g.nick)) { throw { code: "TEST_FAIL", message: "absent optional" }; }
              } else {
                throw { code: "TEST_FAIL", message: "roundtrip" };
              }
              let e1: Form | null = Form$fromJson("{\\"nope\\":1}");
              if (e1 !== null) { throw { code: "TEST_FAIL", message: "extra key" }; }
              let e2: Form | null = Form$fromJson("{");
              if (e2 !== null) { throw { code: "TEST_FAIL", message: "malformed" }; }
              let e3: Form | null = Form$fromJson("42");
              if (e3 !== null) { throw { code: "TEST_FAIL", message: "scalar" }; }
              let e4: Form | null = Form$fromJson("{\\"point\\":{\\"zz\\":1}}");
              if (e4 !== null) { throw { code: "TEST_FAIL", message: "nested extra" }; }
              return null;
            }
            """, Map.of("src/lib.deal", """
                // @jsonable
                export class Point {
                  x: int = 0;
                  y: int = 0;
                  opt?: int | null;
                }
                """));

        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String libSource = sourceOf(emission, "lib");
        String appSource = sourceOf(emission, "app");
        // The effective map selection keeps the class groups' coordinate-only
        // mappings ordered and inside their own artifacts.
        for (JsModuleArtifact artifact : JsSemanticEmitter
                .emitProductionProject(fixture.emissionInput(true)).modules()) {
            List<SourceMapGenerator.Mapping> mappings = artifact.mappings();
            check(mappings != null && !mappings.isEmpty(),
                "the mapped class emission records coordinates for "
                    + artifact.artifactRelativePath());
            if (mappings != null) {
                int previousLine = 0;
                boolean ordered = true;
                for (SourceMapGenerator.Mapping mapping : mappings) {
                    if (mapping.generatedLine() < previousLine) {
                        ordered = false;
                    }
                    previousLine = mapping.generatedLine();
                }
                check(ordered, "the class emission's mappings stay in"
                    + " generated order (" + artifact.artifactRelativePath()
                    + ")");
            }
        }
        Path classLiveRoot = root.resolve("build/js");
        verifyEmissionSidecars(fixture,
            JsSemanticEmitter.emitProductionProject(
                fixture.emissionInput(true, classLiveRoot)),
            classLiveRoot, root);
        check(libSource != null && appSource != null,
            "the class fixture emits both implementation artifacts");
        check(libSource != null && libSource.contains(
                "$rt.registerFactory(\"lib\", 0, ($provided) => {"),
            "the declaring module registers its CLASS_FACTORY as a private"
                + " factory entry (defaults-only transfer)");
        check(libSource != null && libSource.contains(
                "$rt.publishModule(\"lib\", \"Point\", { $kind: \"class\","
                    + " $classname: \"@src/Point\" });"),
            "the exported class publishes its META value under its own name");
        check(libSource != null && libSource.contains(
                "$rt.publishModule(\"lib\", \"Point$new\","),
            "the exported class publishes the full-construction C$new");
        check(libSource != null && libSource.contains(
                "$rt.publishModule(\"lib\", \"Point$fields\", $fPoint);"),
            "the @jsonable class publishes the hidden C$fields descriptor"
                + " export");
        check(appSource != null && appSource.contains(
                "$rt.registerFactory(\"app\",") 
                && !appSource.contains("$rt.registerFactory(\"lib\","),
            "a module registers only its own factories (the imported class's"
                + " transfer stays in its declaring module)");
        check(appSource != null && appSource.indexOf(
                "$rt.publishModule(\"app\", \"Form$fields\"")
                < appSource.indexOf("if ($require.main === $module)"),
            "the class surface publishes before the direct-run entry shim");
        check(appSource != null && appSource.contains(
                "$rt.callFactory(\"lib\", 0, ["),
            "a SHARED_FACTORY construction transfers the owner's defaults"
                + " through the private linkage");
        check(appSource != null && appSource.contains(
                "$rt.jsonFromJson(\"@src/Form\", $fForm, null,")
                && appSource.contains(
                    "$rt.readModule(\"lib\", \"Point$fields\")"),
            "the generated JSON helper drives the canonical walker over the"
                + " class's own descriptor array and reads the imported"
                + " provider's published descriptor array");
        // ISSUE-0811: the public roles keep their exact keys and signatures.
        check(appSource != null && appSource.contains(
                "$rt.publishModule(\"app\", \"Form$new\", (provided, $file,"
                    + " $line, $column) => $rt.classPlan(\"@src/Form\", $pForm,"
                    + " provided, $file, $line, $column));"),
            "the public constructor is the raw four-parameter"
                + " C$new(provided,file,line,column) over the class plan");
        check(appSource != null && appSource.contains(
                "$rt.function(\"(string)->?@src/Form\",")
                && appSource.contains(
                    "$rt.function(\"(@src/Form)->string\","),
            "the generated JSON helpers carry the pinned declared signatures");
        check(appSource != null && appSource.contains("\"Fault$new\",")
                && !appSource.contains("\"Fault$fields\""),
            "a non-jsonable exported class publishes META and C$new and no"
                + " C$fields descriptor export");
        check(appSource != null && appSource.contains("descriptor: \"Error\"")
                && appSource.contains("descriptor: \"[Error]\"")
                && !appSource.contains("@/Error")
                && !appSource.contains("@$builtin"),
            "the builtin Error descriptor is exactly Error, recursively, and"
                + " no superseded alias spelling occurs in the artifact");
        // The app module's own factory entry id (the project-wide registry
        // allocation): the drive observes that entry, never a guessed id.
        java.util.regex.Matcher factoryId = Pattern.compile(
            "\\$rt\\.registerFactory\\(\"app\", (\\d+), \\(\\$provided\\)")
            .matcher(appSource == null ? "" : appSource);
        boolean factoryRegistered = factoryId.find();
        check(factoryRegistered,
            "the entry module registers its exported class's factory entry");
        String appFactoryId = factoryRegistered ? factoryId.group(1) : "";
        JsProjectEmission second =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        for (int i = 0; i < emission.modules().size(); i++) {
            checkEq(emission.modules().get(i).source(),
                second.modules().get(i).source(),
                "the " + emission.modules().get(i).artifactRelativePath()
                    + " class emission is byte-identical across independent"
                    + " calls");
        }

        if (!node) {
            skip("the class drive needs the real Node binary");
            return;
        }
        Path out = stage(fixture, "classes", emission);
        Files.writeString(out.resolve("driver.js"), """
            const rt = require("./deal/runtime.js");
            // The private linkage observes registration and transfer
            // without adding a public surface: the emitted defaults thunks
            // reached through the factory are the only cross-module calls.
            let factoryCalls = 0;
            const realCallFactory = rt.callFactory;
            rt.callFactory = function () {
              factoryCalls++;
              return realCallFactory.apply(rt, arguments);
            };
            const app = require("./app.js");
            const lib = require("./lib.js");
            const out = {state: rt.moduleState("app"),
              exports: Object.keys(app).sort().join(","),
              meta: app.Form, fields: app.Form$fields,
              libExports: Object.keys(lib).sort().join(","),
              factoryCallsAtRequire: factoryCalls};
            // The public roles: exact keys, arity and signatures.
            out.formKeys = Object.keys(app.Form);
            out.newArity = app.Form$new.length;
            out.newIsRaw = typeof app.Form$new === "function"
              && app.Form$new.$kind === undefined;
            out.sigs = {from: app.Form$fromJson.$sig,
              to: app.Form$toJson.$sig,
              libFrom: lib.Point$fromJson.$sig,
              libTo: lib.Point$toJson.$sig,
              fromKind: app.Form$fromJson.$kind,
              toKind: app.Form$toJson.$kind,
              faultFields: app.Fault$fields === undefined};
            out.fieldRoles = app.Form$fields.map(function (e) {
              return Object.keys(e).join(",");
            });
            // Registration executes no default: the app factory transfers
            // the omitted defaults only when it is called, and the nested
            // provider default is its one linkage call.
            const beforeTransfer = factoryCalls;
            const transfer = realCallFactory.call(rt, "app",
              __APP_FACTORY_ID__, []);
            out.transfer = {keys: Object.keys(transfer),
              kind: transfer.$kind, cls: transfer.$classname,
              nested: transfer.point.$classname,
              calls: factoryCalls - beforeTransfer};
            // The public C$new performs full construction (provided defaults).
            const made = app.Form$new({name: "Eve"}, "d.js", 1, 1);
            out.made = {name: made.name, score: made.score,
              pointX: made.point.x, manyLen: made.many.length,
              kind: made.$kind, cls: made.$classname};
            // An imported provider constructs through its own public C$new.
            const pt = lib.Point$new({x: 5}, "d.js", 2, 2);
            out.point = {x: pt.x, y: pt.y};
            try {
              app.Form$new({zz: 1}, "d.js", 3, 3);
              out.extra = "none";
            } catch (e) {
              out.extra = e.$dealCode + ":" + e.message;
            }
            // The provided input is never mutated and a failed construction
            // publishes no instance; an omitted origin stays absent.
            const given = {name: "Ada"};
            const snapshot = JSON.stringify(given);
            const madeGiven = app.Form$new(given, "d.js", 40, 40);
            out.given = {unchanged: JSON.stringify(given) === snapshot,
              kind: given.$kind, madeName: madeGiven.name,
              madePoint: madeGiven.point.$classname};
            const bad = {zz: 1, extra: 2};
            const badSnapshot = JSON.stringify(bad);
            // The raw host ABI adapter scans the provided keys before it
            // applies any omitted default, so the nested provider default
            // must not run for the rejected attempt.
            const beforeBad = factoryCalls;
            try {
              app.Form$new(bad, "d.js", 41, 41);
              out.badExtra = "none";
            } catch (e) {
              out.badExtra = e.$dealCode + ":" + e.message + ":at="
                + e.file + ":" + e.line + ":" + e.column;
            }
            out.bad = {unchanged: JSON.stringify(bad) === badSnapshot,
              kind: bad.$kind, calls: factoryCalls - beforeBad};
            const typed = {name: 5};
            const typedSnapshot = JSON.stringify(typed);
            try {
              app.Form$new(typed, "d.js", 42, 42);
              out.typed = "none";
            } catch (e) {
              out.typed = e.$dealCode + ":" + e.message + ":at="
                + e.file + ":" + e.line + ":" + e.column;
            }
            out.typedInput = {unchanged:
              JSON.stringify(typed) === typedSnapshot, kind: typed.$kind};
            let absent;
            try {
              app.Form$new({zz: 1});
              absent = "none";
            } catch (e) {
              absent = [e.file === undefined, e.line === undefined,
                e.column === undefined];
            }
            out.absent = absent;
            // The optional-nullable three states through the public helpers.
            const nilStates = [lib.Point$new({x: 1}, "d.js", 10, 10),
              lib.Point$new({x: 1, opt: null}, "d.js", 11, 11),
              lib.Point$new({x: 1, opt: 2}, "d.js", 12, 12)];
            out.nilStates = nilStates.map(function (p) {
              return [rt.has(p, "opt"), rt.optRead(p.opt)];
            });
            out.nilJson = nilStates.map(function (p, i) {
              return lib.Point$toJson.$f(p, "d.js", 20 + i, 20 + i);
            });
            const decodedMissing = lib.Point$fromJson.$f("{\\"x\\":1}",
              "d.js", 30, 30);
            const decodedNull = lib.Point$fromJson.$f(
              "{\\"x\\":1,\\"opt\\":null}", "d.js", 31, 31);
            out.decodedStates = [
              [rt.has(decodedMissing, "opt"),
                rt.optRead(decodedMissing.opt)],
              [rt.has(decodedNull, "opt"), rt.optRead(decodedNull.opt)]];
            // The builtin Error identity is exactly Error, and each
            // construction receives its own Error[] default array.
            const fault = app.Fault$new({}, "d.js", 50, 50);
            const secondFault = app.Fault$new({}, "d.js", 51, 51);
            out.fault = {errKind: fault.err.$kind,
              errCls: fault.err.$classname, code: fault.err.code,
              errsLen: fault.errs.length,
              fresh: secondFault.errs !== fault.errs,
              metaKeys: Object.keys(app.Fault)};
            // The generated wrappers keep the public helper ABI.
            const text = app.Form$toJson.$f(made, "d.js", 4, 4);
            const back = app.Form$fromJson.$f(text, "d.js", 5, 5);
            out.json = text;
            out.back = {name: back.name, pointX: back.point.x,
              nickPresent: rt.has(back, "nick")};
            out.nullFailures = [
              app.Form$fromJson.$f("{\\"nope\\":1}", "d.js", 6, 6),
              app.Form$fromJson.$f("{", "d.js", 7, 7),
              app.Form$fromJson.$f("42", "d.js", 8, 8)];
            // A non-JSON value raises the walk arm at the call-site origin.
            made.point = made;
            try {
              app.Form$toJson.$f(made, "d.js", 77, 88);
              out.walk = "none";
            } catch (e) {
              out.walk = e.$dealCode + ":" + e.message + ":at="
                + e.file + ":" + e.line + ":" + e.column;
            }
            console.log(JSON.stringify(out));
            """.replace("__APP_FACTORY_ID__", appFactoryId));
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the class driver exits 0: " + run.output());
        // The driver only requires the entry, so the emitted source-literal
        // checks in main run on the real entry artifact itself: running it
        // makes it the direct-run entry and the guard executes main once.
        NodeRun entry = runNode(out, entryPath(out));
        checkEq(0, entry.status(),
            "the class entry artifact executes its source literals under the"
                + " direct-run guard and exits 0: " + entry.output());
        check(run.output().contains("\"state\":\"INITIALIZED\""),
            "the entry module's init completed: " + run.output());
        check(run.output().contains(
                "\"exports\":\"Fault,Fault$new,Form,Form$fields,Form$fromJson,"
                    + "Form$new,Form$toJson,main\""),
            "the module surface carries the class metadata, the constructor,"
                + " the hidden descriptor export and the generated wrappers"
                + " alongside the declared exports: " + run.output());
        check(run.output().contains("\"formKeys\":[\"$kind\",\"$classname\"]")
                && run.output().contains("\"newArity\":4")
                && run.output().contains("\"newIsRaw\":true"),
            "the class META is the exact two-key pair and C$new is the raw"
                + " four-parameter callable: " + run.output());
        check(run.output().contains("\"sigs\":{\"from\":"
                + "\"(string)->?@src/Form\",\"to\":\"(@src/Form)->string\","
                + "\"libFrom\":\"(string)->?@src/Point\",\"libTo\":"
                + "\"(@src/Point)->string\",\"fromKind\":\"function\","
                + "\"toKind\":\"function\",\"faultFields\":true}"),
            "the generated JSON helpers are function wrappers carrying the"
                + " exact declared signatures and only jsonable classes carry"
                + " C$fields: " + run.output());
        check(run.output().contains("\"fieldRoles\":[\"name,jtype,optional,"
                + "nullable,hasDefault,evaluator\",\"name,jtype,optional,"
                + "nullable,hasDefault\",\"name,jtype,optional,nullable,"
                + "hasDefault,evaluator\",\"name,jtype,className,fields,"
                + "optional,nullable,hasDefault,evaluator\",\"name,jtype,"
                + "element,optional,nullable,hasDefault,evaluator\"]"),
            "the descriptor array keeps the pinned per-role keys in declaration"
                + " order: " + run.output());
        check(run.output().contains("\"factoryCallsAtRequire\":0"),
            "registration executes no default (no factory call during init): "
                + run.output());
        check(run.output().contains("\"transfer\":{\"keys\":[\"name\","
                + "\"score\",\"point\",\"many\"],\"nested\":"
                + "\"@src/Point\",\"calls\":1}"),
            "the defaults-only transfer carries the omitted required defaults"
                + " in declaration order, no optional and no public tag, and"
                + " runs its nested provider default: " + run.output());
        check(run.output().contains("\"given\":{\"unchanged\":true,"
                + "\"madeName\":\"Ada\",\"madePoint\":\"@src/Point\"}"),
            "C$new copies the provided input without mutating it: "
                + run.output());
        check(run.output().contains("\"badExtra\":\"E8007:extra field 'zz'"
                + " in class '@src/Form':at=d.js:41:41\"")
                && run.output().contains("\"bad\":{\"unchanged\":true,"
                    + "\"calls\":0}"),
            "a failed extra-key construction rejects before any omitted"
                + " default runs, publishes no instance and mutates nothing: "
                + run.output());
        check(run.output().contains("\"typed\":\"E8001:expected string"
                + ":at=d.js:42:42\"")
                && run.output().contains("\"typedInput\":{\"unchanged\":"
                + "true}"),
            "an invalid provided carrier publishes no instance and mutates"
                + " nothing: " + run.output());
        check(run.output().contains("\"absent\":[true,true,true]"),
            "an omitted call-site origin stays absent on the failure: "
                + run.output());
        check(run.output().contains("\"nilStates\":[[false,null],"
                + "[true,null],[true,2]]"),
            "the optional-nullable three states survive public construction"
                + " (missing differs from present null): " + run.output());
        check(run.output().contains("\"nilJson\":[\"{\\\"x\\\":1,"
                + "\\\"y\\\":0}\",\"{\\\"x\\\":1,\\\"y\\\":0,"
                + "\\\"opt\\\":null}\",\"{\\\"x\\\":1,\\\"y\\\":0,"
                + "\\\"opt\\\":2}\"]"),
            "toJson omits the missing optional, keeps a present null and"
                + " serializes a present value: " + run.output());
        check(run.output().contains("\"decodedStates\":[[false,null],"
                + "[true,null]]"),
            "fromJson keeps the missing and present-null states distinct: "
                + run.output());
        check(run.output().contains("\"fault\":{\"errKind\":\"class\","
                + "\"errCls\":\"Error\",\"code\":\"\",\"errsLen\":0,"
                + "\"fresh\":true,"
                + "\"metaKeys\":[\"$kind\",\"$classname\"]}"),
            "an Error field constructs the exact builtin Error instance and"
                + " each construction receives its own Error[] default: "
                + run.output());
        check(run.output().contains("\"meta\":{\"$kind\":\"class\","
                + "\"$classname\":\"@src/Form\"}"),
            "the exported class value is exactly the META pair with its"
                + " canonical public identity: " + run.output());
        check(run.output().contains("\"name\":\"Eve\",\"score\":0,"
                + "\"pointX\":0,\"manyLen\":0"),
            "C$new constructs with per-attempt defaults, nested class"
                + " defaults included: " + run.output());
        check(run.output().contains("\"extra\":\"E8007:extra field 'zz' in"
                + " class '@src/Form'\""),
            "C$new rejects an extra provided key with the pinned E8007 at"
                + " the call-site origin: " + run.output());
        check(run.output().contains("\"point\":{\"x\":5,\"y\":0}"),
            "an imported provider's C$new constructs through its own"
                + " defaults: " + run.output());
        check(run.output().contains("\"json\":\"{\\\"name\\\":\\\"Eve\\\","
                + "\\\"score\\\":0.0,\\\"point\\\":{\\\"x\\\":0,\\\"y\\\":0},"
                + "\\\"many\\\":[]}\""),
            "toJson serializes the declared fields in declaration order"
                + " and omits the absent optional: " + run.output());
        check(run.output().contains("\"back\":{\"name\":\"Eve\","
                + "\"pointX\":0,\"nickPresent\":false}"),
            "fromJson decodes the nested provider class through the imported"
                + " descriptor array and keeps the absent optional absent: "
                + run.output());
        check(run.output().contains("\"nullFailures\":[null,null,null]"),
            "every specified fromJson failure returns the DEAL null: "
                + run.output());
        check(run.output().contains("\"walk\":\"E8001:value at"
                + " point is not JSON serializable: @src/Form:at=d.js:77:88\""),
            "a non-JSON field raises the walk arm at the call-site origin: "
                + run.output());
    }

    // =========================================================================
    // 3b. Failure tuples, throw transport, and the entry presentation
    // =========================================================================

    private static void testFailureTuples(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- complete failure tuples, throw transport and"
            + " the entry presentation --");
        Path root = Files.createTempDirectory(tmpRoot, "failures");
        Fixture fixture = compileFixture(root, """
            export function main(): null {
              let t: table = {a: "x"};
              let n: int = t.a;
              return null;
            }
            """);
        Path out = stage(fixture, "failure-read", false);
        if (!node) {
            skip("failure tuple drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const app = require("./app.js");
            try {
              app.main.$f();
              console.log(JSON.stringify({threw: false}));
            } catch (e) {
              const c = require("./deal/runtime.js");
              console.log(JSON.stringify({threw: true, code: e.code,
                message: e.message, expected: e.expected, actual: e.actual,
                located: typeof e.file === "string" && e.line > 0
                  && e.column > 0,
                frames: typeof e.frames === "string" && e.frames.length > 0,
                absent: !Object.prototype.hasOwnProperty.call(e, "cause")}));
            }
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the failure-tuple driver exits 0: "
            + run.output());
        check(run.output().contains("\"threw\":true"), "the program threw");
        check(run.output().contains("\"code\":\"E8001\""),
            "the contextual table read raises E8001: " + run.output());
        check(run.output().contains("\"expected\":\"int\""),
            "the failure carries the expected token: " + run.output());
        check(run.output().contains("\"actual\":\"string\""),
            "the failure carries the actual token: " + run.output());
        check(run.output().contains("\"located\":true"),
            "the failure carries its authoritative origin: " + run.output());
        check(run.output().contains("\"frames\":true"),
            "the failure carries its frames: " + run.output());
        check(run.output().contains("\"absent\":true"),
            "an absent cause stays absent, never null: " + run.output());

        // The array bounds arm and the throw/catch/rethrow transport through
        // the entry artifact's own uncaught presentation.
        Path boundsRoot = Files.createTempDirectory(tmpRoot, "failure-bounds");
        Fixture bounds = compileFixture(boundsRoot, """
            function boom(): null {
              throw { code: "E9000", message: "boom" };
            }

            export function main(): null {
              let arr: int[] = [1, 2];
              let i: int = 4;
              let x: int = arr[i];
              return null;
            }
            """);
        Path boundsOut = stage(bounds, "failure-bounds", false);
        NodeRun boundsRun = runNode(boundsOut, entryPath(boundsOut));
        checkEq(1, boundsRun.status(), "the bounds failure exits 1");
        check(boundsRun.output().contains("DEAL_ERROR_CODE: E8002"),
            "the bounds arm is E8002: " + boundsRun.output());
        check(boundsRun.output().contains(" at "),
            "the entry presentation keeps the located suffix: "
                + boundsRun.output());

        Path throwRoot = Files.createTempDirectory(tmpRoot, "failure-throw");
        Fixture throwFixture = compileFixture(throwRoot, """
            function boom(): null {
              throw { code: "E9000", message: "boom" };
            }

            export function main(): null {
              try {
                boom();
              } catch (e) {
                throw e;
              }
              return null;
            }
            """);
        Path throwOut = stage(throwFixture, "failure-throw", false);
        NodeRun throwRun = runNode(throwOut, entryPath(throwOut));
        checkEq(1, throwRun.status(), "the rethrown failure exits 1");
        check(throwRun.output().contains("DEAL_ERROR_CODE: E9000 boom at "),
            "the rethrow preserves the code/message/location: "
                + throwRun.output());
    }

    // =========================================================================
    // 3b. Field and generated-JSON execution over the resolved declaration
    //     sites
    // =========================================================================

    /**
     * ISSUE-0813: the field and generated-JSON family under the real Node
     * binary, over the resolved layouts, declaration sites and default
     * factories the admission pinned.
     *
     * <p>The criteria measured here: required/optional reads, writes,
     * deletes and {@code has} preserve missing, present-null and present
     * value; the receiver, key and value of a committing chain evaluate
     * exactly once and a failed array write commits nothing; an imported
     * class's optional field is deleted, re-written and read over the
     * declaring module's own layout; class-valued and class-array-valued
     * field writes commit the resolved provider's defaults;
     * {@code C$fromJson} decodes provided values before the omitted defaults
     * (nested imported defaults run in their declaring module), returns the
     * language null for every specified failure (duplicate key, extra nested
     * key, wrong provided kind, scalar document) while the three optional
     * states stay distinct, and {@code C$toJson} writes the declared order,
     * omits absent optionals, encodes a deep finite table chain without a
     * depth bound and reports the walk failure at the invoking call
     * expression's own origin.</p>
     *
     * <p>It also pins the creation-site identity of a static call: a
     * generated JSON helper (or any declared body) invoked directly keeps
     * resolving its own creation site even when another function holds a
     * function-typed alias of it, and a local alias that is later reassigned
     * changes no direct call of the aliased declaration.</p>
     */
    private static void testFieldJsonExecutionAndCreationSites(Path tmpRoot,
                                                                boolean node)
            throws Exception {
        System.out.println("-- fields and generated JSON over the resolved"
            + " declaration sites, under Node --");
        Path root = Files.createTempDirectory(tmpRoot, "fields-json");
        String appSource = """
            import * as lib from "./lib";

            class Bag {
              items: lib.Point[] = [];
              p: lib.Point = {};
              opt?: string;
              np?: lib.Point | null;
            }

            // @jsonable
            export class Item {
              v: int = 0;
              data: table = {};
            }

            // @jsonable
            export class Form {
              name: string = "";
              point: lib.Point = {};
              many: lib.Point[] = [];
              node?: Item | null;
              data: table = {};
            }

            function b(v: boolean): string {
              if (v) {
                return "T";
              }
              return "F";
            }

            function alpha(n: int): int {
              if (n <= 0) {
                return 0;
              }
              return beta(n - 1) + 1;
            }

            function beta(n: int): int {
              if (n <= 0) {
                return 0;
              }
              return alpha(n - 1) + 1;
            }

            function nine(): int {
              return 9;
            }

            function aliasHelper(): string {
              let a: (v: Item) => string = Item$toJson;
              return a({ v: 7 });
            }

            export function directHelper(): string {
              let first: string = aliasHelper();
              return Item$toJson({ v: 1 }) + "|" + first;
            }

            export function aliasReassign(): int {
              function inner(): int {
                return 1;
              }
              let g: () => int = inner;
              let first: int = inner();
              g = nine;
              let second: int = inner();
              return first + second;
            }

            export function mutual(): int {
              return alpha(5) + beta(3);
            }

            export function nestedGroup(): int {
              function left(n: int): int {
                if (n <= 0) {
                  return 0;
                }
                return right(n - 1) + 1;
              }
              function right(n: int): int {
                if (n <= 0) {
                  return 0;
                }
                return left(n - 1) + 1;
              }
              return left(6);
            }

            export function presence(): string {
              let bag: Bag = {};
              let out: string = "";
              out = out + b(has(bag.opt)) + b(bag.opt === null);
              bag.opt = "x";
              out = out + b(has(bag.opt));
              delete bag.opt;
              out = out + b(has(bag.opt)) + b(bag.opt === null);
              delete bag.opt;
              out = out + b(has(bag.opt));
              return out;
            }

            export function importedFields(): string {
              let p: lib.Point = {};
              let out: string = b(has(p.opt)) + b(p.opt === null);
              delete p.opt;
              out = out + b(has(p.opt));
              p.opt = 9;
              out = out + b(has(p.opt));
              let read: int | null = p.opt;
              if (read !== null) {
                out = out + b(read === 9);
              }
              delete p.opt;
              out = out + b(has(p.opt)) + b(p.opt === null);
              return out;
            }

            export function chains(): string {
              let bag: Bag = {};
              bag.items = [{ x: 1 }];
              let notes: int[] = [];
              function note(n: int): int {
                notes[notes.length] = n;
                return n;
              }
              bag.items[note(0)] = { y: note(2) };
              let after: string = b(bag.items[0].x === 0)
                + b(bag.items[0].y === 2) + b(notes.length === 2);
              let failed: string = "COMMITTED";
              try {
                bag.items[note(3)] = { y: note(4) };
              } catch (e) {
                failed = e.code;
              }
              let state: string = b(bag.items.length === 1)
                + b(bag.items[0].y === 2) + b(notes.length === 4);
              return after + "|" + failed + "|" + state;
            }

            export function fieldWrites(): string {
              let bag: Bag = {};
              bag.p = { x: 4 };
              bag.items = [{ y: 5 }];
              return b(bag.p.x === 4) + b(bag.p.y === 0)
                + b(bag.items.length === 1) + b(bag.items[0].x === 0)
                + b(bag.items[0].y === 5);
            }

            export function jsonStates(): string {
              let missing: Form | null = Form$fromJson("{\\"name\\":\\"A\\"}");
              let nil: Form | null = Form$fromJson(
                "{\\"name\\":\\"A\\",\\"node\\":null}");
              let value: Form | null = Form$fromJson(
                "{\\"name\\":\\"A\\",\\"node\\":{\\"v\\":5}}");
              let out: string = "";
              if (missing !== null) {
                out = out + b(has(missing.node)) + Form$toJson(missing);
              }
              if (nil !== null) {
                out = out + "|" + Form$toJson(nil);
              }
              if (value !== null) {
                out = out + "|" + Form$toJson(value);
              }
              out = out + "|" + b(Form$fromJson(
                "{\\"name\\":\\"A\\",\\"name\\":\\"B\\"}") !== null);
              out = out + b(Form$fromJson("{\\"node\\":{\\"zz\\":1}}") !== null);
              out = out + b(Form$fromJson(
                "{\\"point\\":{\\"x\\":\\"no\\"}}") !== null);
              out = out + b(Form$fromJson("42") !== null);
              out = out + b(Form$fromJson("null") !== null);
              out = out + b(Form$fromJson(
                "{\\"many\\":[{\\"x\\":1},{\\"y\\":2}]}") !== null);
              return out;
            }

            export function deepChain(links: int): string {
              let form: Form = {};
              let cur: table = form.data;
              let i: int = 0;
              while (i < links) {
                let next: table = {};
                cur.next = next;
                cur = next;
                i = i + 1;
              }
              let text: string = Form$toJson(form);
              let back: Form | null = Form$fromJson(text);
              let backText: string = "";
              if (back !== null) {
                backText = Form$toJson(back);
              } else {
                return "NULL";
              }
              if (backText !== text) {
                return "DIFF";
              }
              return "SAME";
            }

            export function toJsonFailure(): string {
              let item: Item = { v: 1 };
              let f: (n: int) => int = function(n: int): int {
                return n;
              };
              item.data.f = f;
              let boom: (v: Item) => string = Item$toJson;
              return boom(item);
            }

            export function toJsonCycle(): string {
              let item: Item = { v: 1 };
              item.data.self = item.data;
              return Item$toJson(item);
            }

            export function main(): null {
              return null;
            }
            """;
        // The failing walk's origin: the invoking call expression inside
        // `toJsonFailure` (1-based line and column of the callee token).
        int failureLine = 0;
        int failureColumn = 0;
        int line = 0;
        for (String text : appSource.split("\n", -1)) {
            line++;
            int callee = text.indexOf("boom(item)");
            if (callee >= 0) {
                failureLine = line;
                failureColumn = callee + 1;
            }
        }
        check(failureLine > 0,
            "the fixture records the failing helper call expression");
        Fixture fixture = compileFixture(root, appSource,
            Map.of("src/lib.deal", """
                // @jsonable
                export class Point {
                  x: int = 0;
                  y: int = 0;
                  opt?: int | null;
                }
                """));
        Path out = stage(fixture, "fields-json", false);
        if (!node) {
            skip("the field/JSON drive needs the real Node binary");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const app = require("./app.js");
            const out = {};
            const step = (name, f) => {
              try {
                out[name] = "ok:" + f();
              } catch (e) {
                out[name] = "fail:" + (e.code || e.name) + ":" + e.message;
              }
            };
            step("directHelper", () => app.directHelper.$f("d.js", 1, 1));
            step("aliasReassign", () => app.aliasReassign.$f("d.js", 2, 2));
            step("mutual", () => app.mutual.$f("d.js", 3, 3));
            step("nestedGroup", () => app.nestedGroup.$f("d.js", 4, 4));
            step("presence", () => app.presence.$f("d.js", 5, 5));
            step("importedFields", () => app.importedFields.$f("d.js", 6, 6));
            step("chains", () => app.chains.$f("d.js", 7, 7));
            step("fieldWrites", () => app.fieldWrites.$f("d.js", 8, 8));
            step("jsonStates", () => app.jsonStates.$f("d.js", 9, 9));
            step("deep", () => app.deepChain.$f(600, "d.js", 10, 10));
            try {
              app.toJsonFailure.$f("d.js", 11, 11);
              out.walk = "no-failure";
            } catch (e) {
              out.walk = e.code + ":" + e.message + ":at=" + e.file + ":"
                + e.line + ":" + e.column;
            }
            try {
              app.toJsonCycle.$f("d.js", 12, 12);
              out.cycle = "no-failure";
            } catch (e) {
              out.cycle = e.code + ":" + e.message;
            }
            console.log("DRIVE:" + JSON.stringify(out));
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the field/JSON driver exits 0: "
            + run.output());
        String report = run.output();
        check(report.contains("\"directHelper\":\"ok:{\\\"v\\\":1,"
                + "\\\"data\\\":{}}|{\\\"v\\\":7,\\\"data\\\":{}}\""),
            "a direct call of a generated JSON helper keeps its own"
                + " creation site while another function holds a"
                + " function-typed alias of it: " + report);
        check(report.contains("\"aliasReassign\":\"ok:2\""),
            "a reassigned local alias changes no direct call of the"
                + " aliased declaration (the creation site, never the alias"
                + " cell, spells the call): " + report);
        check(report.contains("\"mutual\":\"ok:8\""),
            "a mutually recursive declaration group keeps both members"
                + " callable through their own published creation cells: "
                + report);
        check(report.contains("\"nestedGroup\":\"ok:6\""),
            "a nested recursive group keeps its members callable through"
                + " their group cells: " + report);
        check(report.contains("\"presence\":\"ok:FTTFTF\""),
            "required/optional presence: an absent optional reads null and"
                + " has false, a written value is present, a delete makes it"
                + " absent again and a repeated delete is an idempotent"
                + " no-op: " + report);
        check(report.contains("\"importedFields\":\"ok:FTFTTFT\""),
            "an imported class's optional field is deleted, re-written and"
                + " read over its declaring module's own layout, keeping"
                + " present-null distinct from absence: " + report);
        check(report.contains("\"chains\":\"ok:TTT|E8002|TTT\""),
            "one array-element write evaluates the receiver, index and value"
                + " once, commits once, and an out-of-bounds write after the"
                + " same operand evaluation raises E8002 with the length, the"
                + " slot and the operand count unchanged: " + report);
        check(report.contains("\"fieldWrites\":\"ok:TTTTT\""),
            "class-valued and class-array-valued field writes commit with"
                + " the resolved provider's own defaults: " + report);
        check(report.contains("\"jsonStates\":\"ok:F{\\\"name\\\":\\\"A\\\","
                + "\\\"point\\\":{\\\"x\\\":0,\\\"y\\\":0},"
                + "\\\"many\\\":[],\\\"data\\\":{}}"
                + "|{\\\"name\\\":\\\"A\\\",\\\"point\\\":"
                + "{\\\"x\\\":0,\\\"y\\\":0},"
                + "\\\"many\\\":[],\\\"node\\\":null,\\\"data\\\":{}}"
                + "|{\\\"name\\\":\\\"A\\\",\\\"point\\\":"
                + "{\\\"x\\\":0,\\\"y\\\":0},"
                + "\\\"many\\\":[],\\\"node\\\":{\\\"v\\\":5,"
                + "\\\"data\\\":{}},\\\"data\\\":{}}"
                + "|FFFFFT\""),
            "fromJson decodes provided values before the omitted imported"
                + " provider defaults, keeps the three optional states"
                + " distinct, writes the declared order with the absent"
                + " optional omitted, and returns the language null for a"
                + " duplicate key, a nested extra key, a wrong provided kind,"
                + " a scalar document and a top-level null while the"
                + " class-array document decodes: " + report);
        check(report.contains("\"deep\":\"ok:SAME\""),
            "a 600-link finite table chain inside a class table field"
                + " encodes and re-decodes with no depth bound and the exact"
                + " same text: " + report);
        check(report.contains("\"walk\":\"E8001:value at data.f is not JSON"
                + " serializable: function:at="),
            "a non-JSON value in a declared table field raises the closed"
                + " walk tuple: " + report);
        check(report.contains("app.deal:" + failureLine + ":" + failureColumn),
            "the walk failure keeps the invoking call expression's own"
                + " origin (app.deal line " + failureLine + ", column "
                + failureColumn + "), never the generated helper's anchor: "
                + report);
        check(report.contains("\"cycle\":\"E8001:cyclic value cannot be"
                + " encoded as JSON\""),
            "a cyclic table field raises the closed cycle arm: " + report);
    }

    // =========================================================================
    // 4. The private module registry: repeat identity and sticky failure
    // =========================================================================

    private static void testModuleRegistry(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- repeat identity, sticky failure, no partial"
            + " exports, and the identical published surface --");
        Path root = Files.createTempDirectory(tmpRoot, "registry");
        Fixture fixture = compileFixture(root, """
            import * as util from "./util";

            export function main(): null {
              let y: int = util.addOne(2);
              if (y !== 3) { throw { code: "TEST_FAIL", message: "cross" }; }
              return null;
            }
            """, Map.of("src/util.deal", """
                export function addOne(x: int): int {
                  return x + 1;
                }
                """));
        Path out = stage(fixture, "registry", false);
        if (!node) {
            skip("module registry drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const first = require("./util.js");
            const second = require("./util.js");
            console.log(JSON.stringify({
              sameExports: first === second,
              surface: $rt.moduleSurface("util") === first,
              state: $rt.moduleState("util"),
              keys: Object.keys(first).sort(),
            }));
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the registry driver exits 0: " + run.output());
        check(run.output().contains("\"sameExports\":true"),
            "a repeated require returns the identical surface: "
                + run.output());
        check(run.output().contains("\"surface\":true"),
            "the record's surface is the module's CommonJS exports object: "
                + run.output());
        check(run.output().contains("\"state\":\"INITIALIZED\""),
            "a successful initialization is recorded once: " + run.output());
        check(run.output().contains("\"addOne\""),
            "the published export is present under its own key: "
                + run.output());

        // The artifact registers its private record before any
        // implementation dependency is required and republishes the
        // identical surface afterwards.
        String appArtifact = Files.readString(
            root.resolve("emit-registry/app.js"), StandardCharsets.UTF_8);
        check(appArtifact.indexOf("$rt.initModule(\"app\",")
                < appArtifact.indexOf("$require(\"./util\")"),
            "the module record registers before any implementation"
                + " dependency is required");
        String utilArtifact = Files.readString(
            root.resolve("emit-registry/util.js"), StandardCharsets.UTF_8);
        check(utilArtifact.contains(
                "$module.exports = $rt.moduleSurface(\"util\");"),
            "the artifact publishes the record's identical surface");

        // A failed initialization is sticky: a second init body call
        // rethrows the identical saved failure without executing the body
        // again, and the unpublished exports stay empty.
        Files.writeString(out.resolve("driver2.js"), """
            const $rt = require("./deal/runtime.js");
            let runs = 0;
            function body() { runs++; throw new Error("boom"); }
            let first = null;
            let second = null;
            try { $rt.initModule("probe", body); } catch (e) { first = e; }
            const stateAfterFirst = $rt.moduleState("probe");
            try { $rt.initModule("probe", body); } catch (e) { second = e; }
            let third = null;
            try { $rt.initModule("probe", function () { runs = -1; }); }
            catch (e) { third = e; }
            console.log(JSON.stringify({runs: runs,
              identical: first === second && second === third,
              state: stateAfterFirst,
              exportsEmpty: Object.keys($rt.moduleSurface("probe")).length === 0}));
            """, StandardCharsets.UTF_8);
        NodeRun onceRun = runNode(out, "driver2.js");
        checkEq(0, onceRun.status(), "the sticky-failure driver exits 0: "
            + onceRun.output());
        check(onceRun.output().contains("\"runs\":1"),
            "a failed initialization never retries the body: "
                + onceRun.output());
        check(onceRun.output().contains("\"identical\":true"),
            "every later load rethrows the identical saved failure: "
                + onceRun.output());
        check(onceRun.output().contains("\"state\":\"FAILED\""),
            "the failed record's state is FAILED: " + onceRun.output());
        check(onceRun.output().contains("\"exportsEmpty\":true"),
            "a failed initialization discards its unpublished exports: "
                + onceRun.output());
    }

    // =========================================================================
    // 4b. The call/boundary realization: native async, entry ABI and the
    // host-driven callback crossing
    // =========================================================================

    /**
     * ISSUE-0800: one real Promise operation per async call with one awaited
     * completion — the local body, the arity-extension async adapter, the
     * cross-module stored async value, the awaited loop position, the
     * awaited error caught and rethrown — driven through the production
     * async-export ABI under the real Node binary.
     */
    private static void testAsyncCallsAndCompletion(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- native async start/await, the async adapter and"
            + " the cross-module stored async value under Node --");
        Path root = Files.createTempDirectory(tmpRoot, "async-calls");
        Fixture fixture = compileFixture(root, """
            import * as lib from "./lib";

            async function inner(x: int): int {
              return x + 1;
            }

            async function fail(): int {
              throw { code: "E9001", message: "async-boom" };
            }

            export async function run(): int {
              let a: int = await inner(1);
              let g: async (x: int, y: int) => int = inner;
              let b: int = await g(a, 9);
              let stored: async (x: int) => int = lib.fetch;
              let d: int = await stored(10);
              let total: int = 0;
              for (let i: int = 0; i < 3; i = i + 1) {
                total = total + (await inner(i));
              }
              if (a !== 2 || b !== 3 || d !== 21 || total !== 6) {
                throw { code: "TEST_FAIL", message: "async" };
              }
              return a + b + d + total;
            }

            export async function swallowed(): int {
              try {
                let c: int = await fail();
                return c;
              } catch (e) {
                return 0;
              }
            }

            export async function rethrown(): int {
              try {
                let c: int = await fail();
                return c;
              } catch (e) {
                throw e;
              }
            }

            export function main(): null { return null; }
            """, Map.of("src/lib.deal", """
                export async function fetch(n: int): int {
                  return n + 11;
                }
                """));
        Path out = stage(fixture, "async-calls", true);
        if (!node) {
            skip("async call drive needs node");
            return;
        }
        String appSource = Files.readString(out.resolve("app.js"),
            StandardCharsets.UTF_8);
        check(appSource.contains(
                "$rt.function(\"async()->int\", async function "),
            "the async body is emitted as a real async function:"
                + " the awaited completion is the Promise resolution");
        check(appSource.contains(" = await "),
            "the await op consumes the started operation natively");
        check(appSource.contains("$rt.callExport(\"lib\", \"fetch\""),
            "the cross-module async start invokes the callee's published"
                + " entry through the shared export surface");
        check(appSource.contains("async ($p0, $p1, $file, $line, $column)"),
            "the async arity-extension adapter emits an async wrapper"
                + " returning the source's own operation");
        JsProjectEmission mapped = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(true));
        JsModuleArtifact mappedApp = mapped.modules().stream()
            .filter(a -> a.moduleId().equals(new ModuleId("app")))
            .findFirst().orElseThrow();
        check(mappedApp.mappings() != null && mappedApp.mappings().stream()
                .anyMatch(m -> m.sourceLine() == 12),
            "the async start/await group maps to its own local source line");
        check(mappedApp.mappings().stream()
                .anyMatch(m -> m.sourceLine() == 16),
            "the cross-module async call group maps to its own local"
                + " source line");
        Path asyncLiveRoot = root.resolve("build/js");
        verifyEmissionSidecars(fixture,
            JsSemanticEmitter.emitProductionProject(
                fixture.emissionInput(true, asyncLiveRoot)),
            asyncLiveRoot, root);

        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            (async () => {
              const out = {};
              out.run = await $rt.invokeAsyncExport(app, "run", "int");
              out.direct = await app.run.$f();
              out.swallowed =
                await $rt.invokeAsyncExport(app, "swallowed", "int");
              out.rethrown =
                await $rt.invokeAsyncExport(app, "rethrown", "int");
              console.log(JSON.stringify(out));
            })();
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the async driver exits 0: " + run.output());
        check(run.output().contains(
                "\"run\":{\"$ok\":true,\"$value\":32}"),
            "the awaited calls, the async adapter, the loop await and the"
                + " cross-module stored async value carry their declared"
                + " values: " + run.output());
        check(run.output().contains("\"direct\":32"),
            "a host awaiting the published async wrapper directly receives"
                + " the same completion: " + run.output());
        check(run.output().contains(
                "\"swallowed\":{\"$ok\":true,\"$value\":0}"),
            "an awaited DEAL error is caught by the enclosing catch:"
                + " " + run.output());
        check(run.output().contains("\"code\":\"E9001\""),
            "the rethrown awaited error keeps its code: " + run.output());
        check(run.output().contains("\"message\":\"async-boom\""),
            "the rethrown awaited error keeps its message: " + run.output());
        check(run.output().contains("\"line\":8"),
            "the rethrown awaited error keeps its authoritative origin: "
                + run.output());
    }

    /**
     * ISSUE-0800: the awaited failure transport across a module edge — the
     * failing operation's error crosses {@code callExport} and the await
     * with its companion origin, expected/actual pair and frames intact,
     * and an absent cause stays absent.
     */
    private static void testAwaitedFailureTransport(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- the awaited failure transport keeps the"
            + " companion's origin, tuple and frames --");
        Path root = Files.createTempDirectory(tmpRoot, "async-failure");
        Fixture fixture = compileFixture(root, """
            import * as lib from "./lib";

            export async function run(): int {
              try {
                let v: int = await lib.boom();
                return v;
              } catch (e) {
                throw e;
              }
            }

            export function main(): null { return null; }
            """, Map.of("src/lib.deal", """
                export async function boom(): int {
                  let t: table = {a: "x"};
                  let n: int = t.a;
                  return n;
                }
                """));
        Path out = stage(fixture, "async-failure", false);
        if (!node) {
            skip("awaited failure drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            (async () => {
              const r = await $rt.invokeAsyncExport(app, "run", "int");
              const e = r.$error;
              console.log(JSON.stringify({ok: r.$ok, code: e.code,
                message: e.message, expected: e.expected, actual: e.actual,
                companionOrigin: typeof e.file === "string"
                  && e.file.endsWith("src/lib.deal"),
                line: e.line, column: e.column,
                frames: typeof e.frames === "string" && e.frames.length > 0,
                absent: !Object.prototype.hasOwnProperty.call(e, "cause")}));
            })();
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the failure-transport driver exits 0: "
            + run.output());
        check(run.output().contains("\"ok\":false"),
            "the rejected operation is the DEAL-error signal, not a host"
                + " failure: " + run.output());
        check(run.output().contains("\"code\":\"E8001\""),
            "the awaited failure keeps its code: " + run.output());
        check(run.output().contains("\"expected\":\"int\""),
            "the awaited failure keeps its expected token: " + run.output());
        check(run.output().contains("\"actual\":\"string\""),
            "the awaited failure keeps its actual token: " + run.output());
        check(run.output().contains("\"companionOrigin\":true"),
            "the awaited failure keeps the companion's authoritative"
                + " origin: " + run.output());
        check(run.output().contains("\"line\":3"),
            "the awaited failure keeps the failing source line: "
                + run.output());
        check(run.output().contains("\"frames\":true"),
            "the awaited failure keeps its frames: " + run.output());
        check(run.output().contains("\"absent\":true"),
            "an absent cause stays absent, never null: " + run.output());
    }

    /**
     * ISSUE-0800: the entry and async-export ABI — direct execution
     * initializes and runs non-async {@code main(): null} once (with the
     * uncaught-main stderr/status presentation), a bare require only
     * initializes, and every explicit {@code $rt.invokeAsyncExport} runs
     * its own main leg once, selects the exact {@code async()->R} export,
     * and distinguishes DEAL errors from the pinned host-selection
     * failures.
     */
    private static void testAsyncExportAbiAndEntryLegs(Path tmpRoot,
                                                       boolean node)
            throws Exception {
        System.out.println("-- direct-run/require entry legs and the explicit"
            + " invokeAsyncExport selection legs --");
        Path root = Files.createTempDirectory(tmpRoot, "async-abi");
        Fixture fixture = compileFixture(root, """
            export async function oracle(): int { return 7; }
            export function sync(): int { return 1; }
            export async function parameterized(x: int): int { return x; }
            export function main(): null { return null; }
            """);
        Path out = stage(fixture, "async-abi", false);
        if (!node) {
            skip("async-export ABI drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            (async () => {
              let mains = 0;
              let exports = 0;
              const main = app.main;
              app.main = { $kind: "function", $sig: main.$sig,
                $f: async function() { mains++; return main.$f(); } };
              const oracle = app.oracle;
              app.oracle = { $kind: "function", $sig: oracle.$sig,
                $f: async function() { exports++; return oracle.$f(); } };
              const out = {};
              out.success =
                await $rt.invokeAsyncExport(app, "oracle", "int");
              out.missing = await $rt.invokeAsyncExport(app, "nope", "int");
              out.sync = await $rt.invokeAsyncExport(app, "sync", "int");
              out.parameterized =
                await $rt.invokeAsyncExport(app, "parameterized", "int");
              out.mismatch =
                await $rt.invokeAsyncExport(app, "oracle", "null");
              const firstCounts = { mains: mains, exports: exports };
              out.first =
                await $rt.invokeAsyncExport(app, "oracle", "int");
              out.firstDelta = { mains: mains - firstCounts.mains,
                exports: exports - firstCounts.exports };
              const secondCounts = { mains: mains, exports: exports };
              out.second =
                await $rt.invokeAsyncExport(app, "oracle", "int");
              out.secondDelta = { mains: mains - secondCounts.mains,
                exports: exports - secondCounts.exports };
              console.log(JSON.stringify(out));
            })();
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the async-export driver exits 0: "
            + run.output());
        check(run.output().contains(
                "\"success\":{\"$ok\":true,\"$value\":7}"),
            "the exact async()->int export completes once with its checked"
                + " value: " + run.output());
        check(run.output().contains(
                "\"missing\":{\"$ok\":false,\"$failure\":\"export not"
                    + " found: nope\"}"),
            "a missing export is the pinned host-selection failure: "
                + run.output());
        check(run.output().contains("sync export: expected"),
            "a sync export is the pinned host-selection failure: "
                + run.output());
        check(run.output().contains("parameterized export: expected"),
            "a parameterized export is the pinned host-selection failure: "
                + run.output());
        check(run.output().contains("descriptor mismatch: expected"),
            "a descriptor-mismatched export is the pinned host-selection"
                + " failure: " + run.output());
        check(run.output().contains("\"firstDelta\":{\"mains\":1,"
                + "\"exports\":1}"),
            "one explicit invocation runs exactly one main leg and one"
                + " export leg: " + run.output());
        check(run.output().contains("\"secondDelta\":{\"mains\":1,"
                + "\"exports\":1}"),
            "a second explicit invocation runs its own main/export legs"
                + " exactly once: " + run.output());

        // A bare require only initializes: the same entry whose main runs
        // once per explicit invocation under the invoker never runs main
        // when it is only required.
        Files.writeString(out.resolve("require.js"), """
            const app = require("./app.js");
            console.log(JSON.stringify({mainSig: app.main.$sig}));
            """, StandardCharsets.UTF_8);
        NodeRun requireRun = runNode(out, "require.js");
        checkEq(0, requireRun.status(), "the require-only driver exits 0: "
            + requireRun.output());
        check(requireRun.output().contains("\"mainSig\":\"()->null\""),
            "a bare require publishes the non-async main wrapper without"
                + " running it: " + requireRun.output());
        NodeRun direct = runNode(out, entryPath(out));
        checkEq(0, direct.status(), "direct execution of the entry exits 0: "
            + direct.output());
        checkEq("", direct.output(),
            "a successful direct run writes nothing");

        // The uncaught-main presentation: one located DEAL_ERROR_CODE line
        // and status 1, with the source Error left untouched.
        Path throwingRoot = Files.createTempDirectory(tmpRoot, "async-main");
        Fixture throwing = compileFixture(throwingRoot, """
            export function main(): null {
              throw { code: "E9001", message: "main-boom" };
            }
            """);
        Path throwingOut = stage(throwing, "async-main", false);
        NodeRun directThrowing = runNode(throwingOut, entryPath(throwingOut));
        checkEq(1, directThrowing.status(),
            "an uncaught main DEAL error exits 1: " + directThrowing.output());
        check(directThrowing.output().startsWith(
                "DEAL_ERROR_CODE: E9001 main-boom at "),
            "the uncaught-main presentation keeps the located suffix: "
                + directThrowing.output());
        checkEq(1, directThrowing.output().split("DEAL_ERROR_CODE", -1).length - 1,
            "the entry guard runs main exactly once: "
                + directThrowing.output());
        Files.writeString(throwingOut.resolve("require.js"), """
            const app = require("./app.js");
            console.log("required");
            """, StandardCharsets.UTF_8);
        NodeRun requireThrowing = runNode(throwingOut, "require.js");
        checkEq(0, requireThrowing.status(),
            "requiring an entry whose main would throw only initializes: "
                + requireThrowing.output());
        check(requireThrowing.output().contains("required"),
            "the require-only load never runs main: "
                + requireThrowing.output());

        // An initialization failure stays distinct from main framing: the
        // raw failure propagates out of the require and a later load
        // rethrows the identical saved failure without re-executing.
        Path initRoot = Files.createTempDirectory(tmpRoot, "async-init");
        Fixture initFailure = compileFixture(initRoot, """
            import * as util from "./util";

            export function main(): null { return util.one(); }
            """, Map.of("src/util.deal", """
                export function one(): null { return null; }
                """));
        Path initOut = stage(initFailure, "async-init", false);
        Files.delete(initOut.resolve("util.js"));
        Files.writeString(initOut.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const out = {};
            let first = null;
            let second = null;
            try { require("./app.js"); } catch (e) { first = e; }
            out.state = $rt.moduleState("app");
            try { require("./app.js"); } catch (e) { second = e; }
            out.identical = first === second;
            out.mainFraming = first !== null
              && String(first.message).indexOf("DEAL_ERROR_CODE") >= 0;
            console.log(JSON.stringify(out));
            """, StandardCharsets.UTF_8);
        NodeRun initRun = runNode(initOut, "driver.js");
        checkEq(0, initRun.status(),
            "the initialization-failure driver exits 0: " + initRun.output());
        check(initRun.output().contains("\"state\":\"FAILED\""),
            "a failed initialization records FAILED: " + initRun.output());
        check(initRun.output().contains("\"identical\":true"),
            "a later load rethrows the identical saved failure: "
                + initRun.output());
        check(initRun.output().contains("\"mainFraming\":false"),
            "an initialization failure is never converted to main-shim"
                + " framing: " + initRun.output());
        NodeRun directInit = runNode(initOut, entryPath(initOut));
        check(directInit.status() != 0,
            "direct execution of a failing initialization fails: "
                + directInit.output());
        check(!directInit.output().contains("DEAL_ERROR_CODE"),
            "direct execution keeps the initialization failure's own"
                + " presentation, not main framing: " + directInit.output());
    }

    /**
     * ISSUE-0800: the host-driven callback crossing — a {@code CALLBACK_INVOKE}
     * record makes the published wrapper the host invocation entry, so a
     * host call runs the recorded {@code HOST_TO_DEAL} parameter cells at
     * their own origins while DEAL-internal calls keep reaching the same
     * lowered body.
     */
    private static void testCallbackCrossing(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- the host-driven callback crossing checks the"
            + " HOST_TO_DEAL parameter cells at their own origins --");
        Path root = Files.createTempDirectory(tmpRoot, "callback");
        Fixture fixture = compileFixture(root, """
            export function cb(x: int): int {
              return x * 2;
            }

            export function main(): null { return null; }
            """, Map.of(), Map.of(), Set.of("cb"));
        Path out = stage(fixture, "callback", false);
        if (!node) {
            skip("callback crossing drive needs node");
            return;
        }
        String appSource = Files.readString(out.resolve("app.js"),
            StandardCharsets.UTF_8);
        check(appSource.contains(
                "$g36 = $rt.checkType(\"int\", $g36, "),
            "the published callback wrapper runs the recorded HOST_TO_DEAL"
                + " cell in its prologue");
        Files.writeString(out.resolve("driver.js"), """
            const app = require("./app.js");
            const out = {};
            out.ok = app.cb.$f(21);
            try {
              out.bad = app.cb.$f("nope");
            } catch (e) {
              out.bad = { code: e.code, expected: e.expected,
                actual: e.actual,
                located: typeof e.file === "string" && e.line > 0
                  && e.column > 0 };
            }
            console.log(JSON.stringify(out));
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the callback driver exits 0: "
            + run.output());
        check(run.output().contains("\"ok\":42"),
            "a host invocation of the published callback runs the body: "
                + run.output());
        check(run.output().contains("\"code\":\"E8001\""),
            "a host argument of the wrong type raises the cell's E8001: "
                + run.output());
        check(run.output().contains("\"expected\":\"int\""),
            "the crossing failure carries the declared expected token: "
                + run.output());
        check(run.output().contains("\"actual\":\"string\""),
            "the crossing failure carries the actual token: "
                + run.output());
        check(run.output().contains("\"located\":true"),
            "the crossing failure carries the cell's own origin: "
                + run.output());
    }

    /**
     * ISSUE-0800 review cycle 1, finding 1: the runtime-resolved dynamic
     * callee — the carrier's private class tag selects the class path and
     * the recorded per-class return cell, the recorded parameter cells run
     * once, and the intrinsic-as-callback shape (the existing JS-lane
     * corpus program) renders and executes. The drive passes a module-level
     * closure, a nested closure, an arity-extension adapter over a body,
     * an adapter over the conversion intrinsic and the intrinsic itself
     * through function-typed parameters, and the same carriers through
     * awaited dynamic starts, all under the real Node binary.
     */
    private static void testDynamicCalleeDispatch(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- the dynamic callee dispatches on the carrier's"
            + " private class tag; the intrinsic-as-callback corpus shape"
            + " executes under Node --");
        Path root = Files.createTempDirectory(tmpRoot, "dynamic");
        Fixture fixture = compileFixture(root, """
            import * as lib from "./dynlib";

            async function inner(x: int): int { return x + 1; }

            async function callIt(f: async (x: int) => int, x: int): int {
              return await f(x);
            }

            async function callWide(f: async (x: int, y: int) => int,
                                    x: int, y: int): int {
              return await f(x, y);
            }

            function factorial(n: int): int {
              if (n === 0) { return 1; }
              return n * factorial(n - 1);
            }

            function one(x: int): int { return x; }

            function applyInt(f: (x: int) => int, x: int): int {
              return f(x);
            }

            function applyNumber(f: (x: int) => number, x: int): number {
              return f(x);
            }

            function applyTwo(f: (x: int, y: int) => int,
                              x: int, y: int): int {
              return f(x, y);
            }

            function applyTwoNumber(f: (x: number, y: number) => int,
                                    x: number, y: number): int {
              return f(x, y);
            }

            function nested(base: int): int {
              let add = function(x: int): int { return x + base; };
              return applyInt(add, 10);
            }

            export function syncResults(): int {
              let a: int = applyInt(factorial, 5);
              let b: int = nested(7);
              let c: number = applyNumber(number, 7);
              let takesTwo: (x: int, y: int) => int = one;
              let d: int = applyTwo(takesTwo, 10, 20);
              let g: (x: number, y: number) => int = int;
              let e: int = applyTwoNumber(g, 9.0, 1.0);
              let h: int = applyInt(lib.bump, 5);
              if (a !== 120 || b !== 17 || c !== 7.0 || d !== 10 || e !== 9
                  || h !== 105) {
                throw { code: "TEST_FAIL", message: "dynamic" };
              }
              return a + b + d + e + h;
            }

            export async function run(): int {
              let a: int = await callIt(inner, 1);
              let wide: async (x: int, y: int) => int = inner;
              let b: int = await callWide(wide, 10, 99);
              let c: int = await callIt(inner, 2);
              let d: int = await callIt(lib.afetch, 1);
              if (a !== 2 || b !== 11 || c !== 3 || d !== 201) {
                throw { code: "TEST_FAIL", message: "dynamic-async" };
              }
              return a + b + c + d;
            }

            export function main(): null { return null; }
            """, Map.of("src/dynlib.deal", """
                export function bump(x: int): int { return x + 100; }

                export async function afetch(x: int): int { return x + 200; }
                """));
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
            "the shared authority runs the dynamic-callee program: "
                + oracle.terminal());
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String artifact = sourceOf(emission, "app");
        check(artifact.contains("$rt.dynClass("),
            "the dynamic call sites resolve the carrier's private class"
                + " tag instead of a descriptor");
        boolean classesTagged = artifact.contains(", \"DEAL_BODY\")")
                && artifact.contains(", \"HOST\")")
                && artifact.contains(", \"ADAPTER\")");
        check(classesTagged,
            "the closure, intrinsic and adapter carriers each carry their"
                + " closed class tag");
        check(artifact.contains(".$src = $src;"),
            "the adapter records the source it resolved for the dispatch's"
                + " per-source cell selection");
        check(artifact.contains("$rt.dynFail("),
            "a carrier without a resolvable class fails closed at the call"
                + " origin");
        Path out = stage(fixture, "dynamic", emission);
        if (!node) {
            skip("the dynamic callee drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            (async () => {
              const out = {};
              out.sync = app.syncResults.$f();
              out.async = await $rt.invokeAsyncExport(app, "run", "int");
              console.log(JSON.stringify(out));
            })();
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the dynamic-callee driver exits 0: "
            + run.output());
        check(run.output().contains("\"sync\":261"),
            "a closure, a nested closure, a cross-module published entry,"
                + " the conversion intrinsic and the two adapter carriers"
                + " cross their dynamic call sites with the declared"
                + " results: " + run.output());
        check(run.output().contains("\"async\":{\"$ok\":true,"
                + "\"$value\":217}"),
            "the awaited dynamic starts run the same carriers once and"
                + " complete at the single await: " + run.output());

        // The existing JS-lane corpus program the migration must not lose:
        // the function-typed parameter's dynamic call dispatches the
        // conversion-intrinsic carrier under the real Node binary.
        Fixture corpus = compileFixture(
            Files.createTempDirectory(tmpRoot, "corpus-callback"),
            ConformanceHarnessMetadata.stripClassificationHeaders(
                Files.readString(Path.of("test", "conformance",
                    "backend-runtime", "functions",
                    "intrinsic-as-callback.deal"), StandardCharsets.UTF_8)));
        JsProjectEmission corpusEmission = JsSemanticEmitter
            .emitProductionProject(corpus.emissionInput(false));
        check(corpusEmission.modules().size() == 1,
            "the corpus program renders one artifact");
        Path corpusOut = stage(corpus, "corpus-callback", corpusEmission);
        if (node) {
            Files.writeString(corpusOut.resolve("driver.js"), """
                const app = require("./app.js");
                console.log(JSON.stringify({
                  value: app.test_intrinsic_as_callback.$f()}));
                """, StandardCharsets.UTF_8);
            NodeRun corpusRun = runNode(corpusOut, "driver.js");
            checkEq(0, corpusRun.status(), "the intrinsic-as-callback corpus"
                + " program exits 0: " + corpusRun.output());
            check(corpusRun.output().contains("\"value\":7"),
                "the corpus program's passed-and-called conversion intrinsic"
                    + " returns its converted value: " + corpusRun.output());
        } else {
            skip("the intrinsic-as-callback corpus drive needs node");
        }
    }

    /**
     * ISSUE-0800 review cycle 2, finding 1: one adapt site that executes
     * more than once creates one wrapper per execution, and each wrapper
     * records the source it resolved on its own instance. A retained
     * earlier wrapper therefore still names its own source class for the
     * dynamic dispatch's per-source return cell selection; recording the
     * source on the adapt op's reusable result slot would let a later
     * execution overwrite the earlier carrier's record and fail the
     * retained wrapper closed with a spurious E8001.
     */
    private static void testAdapterPerExecutionSource(Path tmpRoot,
                                                      boolean node)
            throws Exception {
        System.out.println("-- one adapt site executed twice records the"
            + " resolved source on each created wrapper instance --");
        Path root = Files.createTempDirectory(tmpRoot, "adapter-fresh");
        Fixture fixture = compileFixture(root, """
            function one(x: int): int { return x; }

            async function aone(x: int): int { return x; }

            export function loopAdapters(): int {
              let fs: ((x: int, y: int) => int)[] = [];
              let i: int = 0;
              while (i < 2) {
                let f: (x: int, y: int) => int = one;
                fs[i] = f;
                i = i + 1;
              }
              let a: int = fs[0](1, 2);
              let b: int = fs[1](10, 20);
              if (a !== 1 || b !== 10) {
                throw { code: "TEST_FAIL", message: "retained" };
              }
              return a + b;
            }

            export async function asyncLoopAdapters(): int {
              let fs: (async (x: int, y: int) => int)[] = [];
              let i: int = 0;
              while (i < 2) {
                let f: async (x: int, y: int) => int = aone;
                fs[i] = f;
                i = i + 1;
              }
              let a: int = await fs[0](1, 2);
              let b: int = await fs[1](10, 20);
              if (a !== 1 || b !== 10) {
                throw { code: "TEST_FAIL", message: "retained-async" };
              }
              return a + b;
            }

            export function main(): null { return null; }
            """);
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
            "the shared authority runs the retained adapters: "
                + oracle.terminal());
        JsProjectEmission emission = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(false));
        String artifact = sourceOf(emission, "app");
        check(artifact.contains("const $aw"),
            "the adapter wrapper is created through a fresh per-execution"
                + " binding the invocation records its source on");
        check(Pattern.compile("\\$aw\\d+\\.\\$src = \\$src;")
                .matcher(artifact).find(),
            "the resolved source is recorded on the created wrapper"
                + " instance, not on the reusable result slot");
        check(!Pattern.compile("\\$s\\d+\\.\\$src")
                .matcher(artifact).find(),
            "no adapter invocation writes the source through the adapt op's"
                + " result slot");
        Path out = stage(fixture, "adapter-fresh", emission);
        if (!node) {
            skip("the retained-adapter drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            (async () => {
              const sync = app.loopAdapters.$f();
              const async = await $rt.invokeAsyncExport(app,
                "asyncLoopAdapters", "int");
              console.log(JSON.stringify({sync, async}));
            })();
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "both retained loop-created adapters call"
            + " their own sources dynamically: " + run.output());
        check(run.output().contains("\"sync\":11"),
            "the two retained sync adapters keep their own resolved source"
                + " (no spurious E8001 for the earlier wrapper): "
                + run.output());
        check(run.output().contains("\"async\":{\"$ok\":true,"
                + "\"$value\":11}"),
            "the two retained async adapters complete through the same"
                + " per-instance record: " + run.output());
    }

    /**
     * ISSUE-0800 review cycle 1, finding 2: the recorded host-family cells.
     * The intrinsic call's parameter cell runs the E8010
     * {@code HOST_PARAMETER} composition (the runtime cell the typed host
     * wrapper's own parameter arm composes) instead of a masking E8001
     * generic check, and a deferred contextual read passes the raw value to
     * the consuming declared cell, whose own origin the artifact reports
     * (the shared oracle's rule and origin) instead of the read site.
     */
    private static void testHostParameterCellAndDeferredReads(Path tmpRoot,
                                                              boolean node)
            throws Exception {
        System.out.println("-- the HOST_PARAMETER cell and the deferred"
            + " contextual read realize the recorded family/origin --");
        Path root = Files.createTempDirectory(tmpRoot, "host-cell");
        Fixture fixture = compileFixture(root, """
            export function callInt(v: number): int {
              let f: (x: number) => int = int;
              return f(v);
            }

            export function bytesFrom(t: table): bytes {
              let b: bytes = t.v;
              return b;
            }

            export function functionFrom(t: table): (x: int) => int {
              let f: (x: int) => int = t.f;
              let r: int = f(1);
              return f;
            }

            export function main(): null { return null; }
            """);
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal.Success,
            "the shared authority runs the host-cell program's init: "
                + oracle.terminal());
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String artifact = emission.modules().get(0).source();
        check(artifact.contains("$rt.hostParam(1, \"number\""),
            "the intrinsic call's DEAL_TO_HOST+HOST_PARAMETER cell is the"
                + " E8010 composition, not a generic E8001 check");
        check(!artifact.contains("checkType(\"number\""),
            "no generic descriptor check masks the recorded host family");
        Path out = stage(fixture, "host-cell", emission);
        if (!node) {
            skip("the host-cell drive needs node");
            return;
        }
        Files.writeString(out.resolve("driver.js"), """
            const $rt = require("./deal/runtime.js");
            const app = require("./app.js");
            const out = {};
            const tuple = function (e) {
              return { code: e.code, message: e.message,
                expected: e.expected, actual: e.actual, file: e.file,
                line: e.line, column: e.column };
            };
            out.admitted = $rt.hostParam(1, "int", 7, "p.deal", 1, 1);
            out.nullAdmitted = $rt.hostParam(1, "?int", null, "p.deal", 2, 1)
              === null;
            try {
              $rt.hostParam(1, "bytes", 5, "p.deal", 10, 10);
            } catch (e) {
              out.cell = { code: e.code, message: e.message,
                expected: e.expected, actual: e.actual, file: e.file,
                line: e.line, column: e.column };
            }
            try {
              app.bytesFrom.$f(new Map([["v", 5]]));
            } catch (e) {
              out.bytes = { code: e.code, message: e.message, file: e.file,
                line: e.line, column: e.column };
            }
            try {
              app.functionFrom.$f(new Map([["f", 1]]));
            } catch (e) {
              out.fn = { code: e.code, message: e.message, file: e.file,
                line: e.line, column: e.column };
            }
            // The host parameter/return arms' carrier-kind projection (the
            // canonical failure projection's LANGUAGE_NULL -> table token):
            // the DEAL language-null sentinel crossing the host boundary is
            // a Lua table carrier, exactly like __rt.__NULL on LuaJIT and the
            // JVM __hostKind(null).
            try {
              $rt.hostParam(1, "int", null, "p.deal", 11, 1);
            } catch (e) {
              out.nullParam = tuple(e);
            }
            try {
              $rt.hostParam(1, "int", undefined, "p.deal", 12, 1);
            } catch (e) {
              out.nilParam = tuple(e);
            }
            try {
              $rt.hostFunction("()->int", function () {
                return null;
              }).$f("p.deal", 13, 1);
            } catch (e) {
              out.nullReturn = tuple(e);
            }
            // The loadHost shape guards keep their raw-surface vocabulary
            // (the JS value the host supplied, never the language carrier
            // kind): a raw number is number, the JS null is null and the
            // absent marker nil.
            out.rawShapes = {};
            for (const [name, raw] of [["number", 17], ["null", null],
                ["nil", undefined]]) {
              try {
                $rt.loadHost(raw, {}, "host/raw", "p.deal", 1, 1);
              } catch (e) {
                out.rawShapes[name] = tuple(e);
              }
            }
            (async () => {
              try {
                await $rt.hostAsyncFunction("async(int)->int", function (x) {
                  return Promise.resolve(x);
                }).$f(null, "p.deal", 14, 1);
              } catch (e) {
                out.nullAsyncParam = tuple(e);
              }
              try {
                await $rt.hostAsyncFunction("async()->int", function () {
                  return null;
                }).$f("p.deal", 15, 1);
              } catch (e) {
                out.asyncShape = tuple(e);
              }
              console.log(JSON.stringify(out));
            })();
            """, StandardCharsets.UTF_8);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the host-cell driver exits 0: "
            + run.output());
        check(run.output().contains("\"admitted\":7"),
            "the host-parameter cell returns the admitted value: "
                + run.output());
        check(run.output().contains("\"nullAdmitted\":true"),
            "the nullable host position admits the language null: "
                + run.output());
        check(run.output().contains("\"nullParam\":{\"code\":\"E8010\","
                + "\"message\":\"parameter 1 type mismatch: expected int\","
                + "\"expected\":\"int\",\"actual\":\"table\","
                + "\"file\":\"p.deal\",\"line\":11,\"column\":1}"),
            "a language-null host parameter reports the canonical table"
                + " carrier kind (the LuaJIT __carrierKind(__NULL) and JVM"
                + " __hostKind(null) token, never the JS null spelling): "
                + run.output());
        check(run.output().contains("\"nilParam\":{\"code\":\"E8010\","
                + "\"message\":\"parameter 1 type mismatch: expected int\","
                + "\"expected\":\"int\",\"actual\":\"nil\""),
            "the absent host parameter keeps the nil carrier kind: "
                + run.output());
        check(run.output().contains("\"nullReturn\":{\"code\":\"E8010\","
                + "\"message\":\"return value 1 type mismatch: expected"
                + " int\",\"expected\":\"int\",\"actual\":\"table\""),
            "a host return of the language null reports the canonical"
                + " table carrier kind: " + run.output());
        check(run.output().contains("\"nullAsyncParam\":{\"code\":"
                + "\"E8010\",\"message\":\"parameter 1 type mismatch:"
                + " expected int\",\"expected\":\"int\","
                + "\"actual\":\"table\""),
            "an async host parameter crossing the language null reports"
                + " the canonical table carrier kind: " + run.output());
        check(run.output().contains("\"asyncShape\":{\"code\":\"E8010\","
                + "\"message\":\"host async function must return an async"
                + " operation, got table\",\"expected\":\"async operation\","
                + "\"actual\":\"table\""),
            "the async-shape cell keeps the carrier-kind projection: "
                + run.output());
        check(run.output().contains("\"rawShapes\":{\"number\":{\"code\":"
                + "\"E8011\",\"message\":\"host module did not return a"
                + " module object\",\"expected\":\"table\","
                + "\"actual\":\"number\""),
            "the raw loadHost shape guard keeps the raw number token: "
                + run.output());
        check(run.output().contains("\"null\":{\"code\":\"E8011\","
                + "\"message\":\"host module did not return a module"
                + " object\",\"expected\":\"table\",\"actual\":\"null\""),
            "the raw loadHost shape guard keeps the JS null token: "
                + run.output());
        check(run.output().contains("\"nil\":{\"code\":\"E8011\","
                + "\"message\":\"host module did not return a module"
                + " object\",\"expected\":\"table\",\"actual\":\"nil\""),
            "the raw loadHost shape guard keeps the absent/nil token: "
                + run.output());
        check(run.output().contains("\"cell\":{\"code\":\"E8010\","
                + "\"message\":\"parameter 1 type mismatch: expected"
                + " bytes\",\"expected\":\"bytes\",\"actual\":"
                + "\"number\",\"file\":\"p.deal\",\"line\":10,"
                + "\"column\":10}"),
            "the recorded HOST_PARAMETER cell composes the pinned E8010"
                + " tuple at the call origin (the host-bytes-param-mismatch"
                + " corpus expectation): " + run.output());
        check(run.output().contains("\"bytes\":{\"code\":\"E8001\","
                + "\"message\":\"expected bytes\""),
            "the deferred bytes contextual read passes the raw value to"
                + " the consuming declaration cell's own E8001 projection: "
                + run.output());
        check(run.output().contains("\"fn\":{\"code\":\"E8001\","
                + "\"message\":\"expected function\""),
            "the deferred function contextual read passes the raw value to"
                + " the consuming declaration cell's own function row: "
                + run.output());

        // The deferred reads' authoritative origin: the shared authority
        // and the emitted artifact both report the consuming declaration
        // cell's own annotation span, never the read site.
        Fixture bytesFailure = compileFixture(
            Files.createTempDirectory(tmpRoot, "host-cell-bytes"), """
            export function bytesFrom(t: table): bytes {
              let b: bytes = t.v;
              return b;
            }

            export function main(): null {
              let t: table = { v: 5 };
              let b: bytes = bytesFrom(t);
              return null;
            }
            """);
        checkDeferredReadOrigin(bytesFailure, "bytes", node);
        Fixture functionFailure = compileFixture(
            Files.createTempDirectory(tmpRoot, "host-cell-fn"), """
            export function functionFrom(t: table): (x: int) => int {
              let f: (x: int) => int = t.f;
              return f;
            }

            export function main(): null {
              let t: table = { f: 1 };
              let f: (x: int) => int = functionFrom(t);
              return null;
            }
            """);
        checkDeferredReadOrigin(functionFailure, "function", node);
    }

    /**
     * The shared authority and the emitted artifact report the same code,
     * message and origin for one deferred contextual read's consuming
     * declaration crossing.
     */
    private static void checkDeferredReadOrigin(Fixture fixture, String what,
                                                boolean node)
            throws Exception {
        var oracle = deal.semantic.SemanticOracle.executeProjectInits(
            fixture.project(), fixture.tables(), fixture.registries(), null);
        check(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure,
            "the shared authority rejects the deferred " + what
                + " contextual read at the consuming declaration crossing: "
                + oracle.terminal());
        if (!(oracle.terminal()
                instanceof deal.semantic.SemanticRuntimeModel.Terminal
                    .DealFailure failure)) {
            return;
        }
        deal.semantic.SemanticRuntimeModel.ErrorSnapshot snapshot =
            failure.error();
        if (!node) {
            skip("the deferred " + what + " origin drive needs node");
            return;
        }
        Path out = stage(fixture, "deferred-" + what, false);
        NodeRun run = runNode(out, entryPath(out));
        checkEq(1, run.status(), "the deferred " + what + " read exits 1: "
            + run.output());
        check(run.output().contains("DEAL_ERROR_CODE: " + snapshot.code()
                + " " + snapshot.message() + " at " + snapshot.origin()),
            "the deferred " + what + " read raises the shared tuple at the"
                + " consuming declaration cell's own origin (never the read"
                + " site): " + run.output());
    }

    // =========================================================================
    // 5. The HOST surface: one raw init per resolved file, occurrence-owned
    //    typed surfaces, the sanctioned host class construction
    // =========================================================================

    /** The declared host module of the HOST-surface drives. */
    private static final String HOST_CFG_DECLARATION = """
        export class Endpoint {
          path: string = "";
          port?: int;
        }

        export class ServerConfig {
          port: int;
          endpoint: Endpoint;
          tags?: string[];
        }

        export function zeta(): int;
        export function alpha(): int;
        export function describe(cfg: ServerConfig): string;
        export function values(): int[];
        export function apply(f: () => int): int;
        export function sameFn(a: () => int, b: () => int): boolean;
        export async function label(): string;
        """;

    /**
     * The HOST import drive: two source aliases of one resolved host file
     * plus one cross-unit import. One raw Node require runs per resolved
     * file; every source import owns its own typed surface (fresh wrappers,
     * same-import identity, cross-import inequality); the declared reads
     * follow the supplied ascending export-name order; and every call form
     * — direct, stored, namespace/index, host-driven callback and async —
     * selects its occurrence's entry. The host-declaration class
     * construction runs through the recorded occurrence's synthesized
     * {@code C$new} over the host's own defaults.
     */
    private static void testHostOccurrenceSurfaces(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- HOST imports: one raw init, per-import typed"
            + " surfaces, name-sorted declared reads and the host class"
            + " construction --");
        Path root = Files.createTempDirectory(tmpRoot, "host-occ");
        writeFileIn(root, "src/cfg.d.deal", HOST_CFG_DECLARATION);
        Fixture fixture = compileFixture(root, """
            import * as h1 from "host/cfg";
            import * as h2 from "host/cfg";
            import * as util from "./util";

            export function run(): string {
              if (h1.alpha !== h1.alpha) {
                throw { code: "SAME_IMPORT_IDENTITY", message: "same import" };
              }
              if (h1.alpha === h2.alpha) {
                throw { code: "CROSS_IMPORT_INEQUALITY", message: "imports" };
              }
              let direct: int = h1.alpha();
              let f: () => int = h2.alpha;
              let stored: int = f();
              let namespaced: int = h1.zeta();
              if (direct !== 7 || stored !== 7 || namespaced !== 9) {
                throw { code: "HOST_CALL", message: "call forms" };
              }
              let values: int[] = h1.values();
              let sum: int = 0;
              for (let v: int of values) {
                sum = sum + v;
              }
              if (sum !== 6) {
                throw { code: "HOST_ARRAY", message: "array carrier" };
              }
              let applied: int = h2.apply(h1.alpha);
              if (applied !== 107) {
                throw { code: "HOST_CALLBACK", message: "callback" };
              }
              if (!h1.sameFn(h1.alpha, h1.alpha)) {
                throw { code: "HOST_IDENTITY", message: "same import" };
              }
              if (h1.sameFn(h1.alpha, h2.alpha)) {
                throw { code: "HOST_IDENTITY", message: "cross import" };
              }
              let e1: h1.Endpoint = { path: "/one" };
              let e2: h2.Endpoint = { path: "/two" };
              let c1: h1.ServerConfig = { port: 7, endpoint: e1 };
              let c2: h2.ServerConfig = { port: 9, endpoint: e2,
                tags: ["a"] };
              let d1: string = h1.describe(c1);
              let d2: string = h2.describe(c2);
              if (d1 !== "/one:7") {
                throw { code: "HOST_CLASS", message: d1 };
              }
              if (d2 !== "/two:9") {
                throw { code: "HOST_CLASS", message: d2 };
              }
              if (util.read() !== 7) {
                throw { code: "CROSS_UNIT_CALL", message: "cross unit" };
              }
              return "ok";
            }

            export async function label2(): string {
              return await h1.label();
            }

            export function main(): null { return null; }
            """, Map.of("src/util.deal", """
            import * as e from "host/cfg";

            export function read(): int {
              return e.alpha();
            }
            """), Map.of("host/cfg",
                root.resolve("src/cfg.d.deal").toAbsolutePath().toString()));
        RuntimeDeclarationSurface surface = RuntimeDeclarationProjection
            .project(fixture.surface(), fixture.identities(), fixture.seeds());
        RuntimeDeclarationSurface.RuntimeDeclarationModule cfg =
            surface.modules().values().iterator().next();
        String endpointId = cfg.classes().get("Endpoint").classId().text();
        String serverConfigId =
            cfg.classes().get("ServerConfig").classId().text();
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String app = sourceOf(emission, "app");
        String util = sourceOf(emission, "util");
        Path hostLiveRoot = root.resolve("build/js");
        verifyEmissionSidecars(fixture,
            JsSemanticEmitter.emitProductionProject(
                fixture.emissionInput(true, hostLiveRoot)),
            hostLiveRoot, root);
        checkEq(2, countOccurrences(app, "$rt.loadHost("),
            "one typed host surface per source HOST import declaration (two"
                + " source aliases of one resolved file emit two loads)");
        checkEq(1, countOccurrences(util, "$rt.loadHost("),
            "the cross-unit import emits its own source occurrence's load");
        check(app.contains("$require(\"./host/cfg\")")
                && !app.contains("$require(\"host/cfg\")"),
            "the emitted host specifier is the resolved file's relative"
                + " require (never an ambient NODE_PATH/node_modules"
                + " resolution): " + app);
        checkEq(2, countOccurrences(app, "\"host/cfg\", \""),
            "each load carries the source import's own module clause for the"
                + " load-time tuple texts: " + app);
        check(app.contains("{\"Endpoint\":"),
            "the declared map starts with the ascending-name first export");
        check(app.indexOf("\"Endpoint\":") < app.indexOf("\"ServerConfig\":")
                && app.indexOf("\"ServerConfig\":") < app.indexOf("\"alpha\":")
                && app.indexOf("\"alpha\":") < app.indexOf("\"apply\":")
                && app.indexOf("\"apply\":") < app.indexOf("\"describe\":")
                && app.indexOf("\"label\":") < app.indexOf("\"sameFn\":")
                && app.indexOf("\"sameFn\":") < app.indexOf("\"values\":")
                && app.indexOf("\"values\":") < app.indexOf("\"zeta\":"),
            "the declared map carries the supplied ascending export-name"
                + " order: " + app);
        check(Pattern.compile("\\$rt\\.loadHost\\(\\$d\\d+, \\{\"Endpoint\": "
                + "\\{ \\$k: \"class\", \\$d: \"" + Pattern.quote(endpointId)
                + "\", \\$fields: \\[").matcher(app).find(),
            "a declared class export carries its identity and field metadata"
                + " in the declared map: " + app);
        check(app.contains("{ name: \"path\", $d: \"string\", optional:"
                + " false, nullable: false, hasDefault: true }"),
            "the declared field entry carries the structural descriptor and"
                + " the optional/nullable/has-default flags: " + app);
        check(app.contains("{ name: \"port\", $d: \"int\", optional: true,"
                + " nullable: false, hasDefault: false }"),
            "the optional declared field entry projects the !required flag");
        check(app.contains("{ name: \"tags\", $d: \"[string]\", optional:"
                + " true, nullable: false, hasDefault: false }"),
            "the array-typed declared field carries its structural array"
                + " descriptor");
        check(app.contains("{ $k: \"function\", $d: \"()->int\" }"),
            "a declared function export carries its structural descriptor");
        check(app.contains("{ $k: \"function\", $d: \"async()->string\" }"),
            "a declared async function export keeps its async signature");
        check(Pattern.compile("= \\$g\\d+\\[\"Endpoint\\$new\"\\]\\(\\$p")
                .matcher(app).find(),
            "the host class construction runs the recorded occurrence"
                + " surface's synthesized C$new: " + app);

        // A declaration link the structural surface does not carry (a
        // producer defect) fails closed with the offending import's own
        // origin, never an emitted load with a guessed declared map.
        try {
            JsSemanticEmitter.emitProductionProject(new JsEmissionInput(
                fixture.project(), fixture.tables(), fixture.registries(),
                fixture.seeds(), fixture.namespaces(),
                new RuntimeDeclarationSurface(Map.of()),
                fixture.emissionInput(false).sourceFiles(), false));
            check(false, "a missing declaration-surface entry fails closed");
        } catch (SharedEmissionFailure failure) {
            passed++;
            check(failure.diagnosticRange() != null,
                "the malformed declaration link's failure carries the"
                    + " offending import's own origin");
            check(failure.getMessage().contains("host.cfg"),
                "the failure names the resolved declaration module: "
                    + failure.getMessage());
        }

        Path out = stage(fixture, "host-occ", emission);
        writeFileIn(out, "host/cfg.js", """
            "use strict";
            const $rt = require("../deal/runtime");
            globalThis.__hostInit = (globalThis.__hostInit || 0) + 1;
            const values = [1, 2, 3];
            module.exports = {
              Endpoint: { $kind: "class", $classname: "%s" },
              Endpoint_defaults: { path: "/", port: $rt.MISSING },
              ServerConfig: { $kind: "class", $classname: "%s" },
              ServerConfig_defaults: { port: 8080, endpoint: $rt.MISSING,
                tags: $rt.MISSING },
              zeta: function () { return 9; },
              alpha: function () { return 7; },
              describe: function (c) { return c.endpoint.path + ":" + c.port; },
              values: function () { return values; },
              apply: function (f) { return f.$f() + 100; },
              sameFn: function (a, b) { return a === b; },
              label: function () { return Promise.resolve("labelled"); },
            };
            """.formatted(endpointId, serverConfigId));
        if (!node) {
            skip("the HOST import drive needs node");
            return;
        }
        writeFileIn(out, "driver.js", """
            const app = require("./app.js");
            (async () => {
              const out = { run: app.run.$f() };
              out.label = await app.label2.$f();
              out.init = globalThis.__hostInit;
              console.log(JSON.stringify(out));
            })().catch((e) => {
              console.log("ERR " + (e && e.message));
            });
            """);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the host drive exits 0: " + run.output());
        check(run.output().contains("\"run\":\"ok\""),
            "every host call form, the array carrier, the host-driven"
                + " callback and both occurrences' class construction run: "
                + run.output());
        check(run.output().contains("\"label\":\"labelled\""),
            "the host async operation completes through the occurrence's"
                + " typed async wrapper: " + run.output());
        check(run.output().contains("\"init\":1"),
            "the raw host file is required exactly once across both source"
                + " aliases and the cross-unit import: " + run.output());

        // The runtime allocation contract the emitted load calls rest on:
        // fresh C$new/C$fields per source import over the preserved raw META
        // reference, identical same-import reads and unequal cross-import
        // function wrappers.
        writeFileIn(out, "surface-probe.js", """
            const $rt = require("./deal/runtime.js");
            const rawEndpoint = { $kind: "class", $classname: "%s" };
            const rawAlpha = function () { return 7; };
            const raw = { Endpoint: rawEndpoint, Endpoint_defaults: { path: "/" },
              alpha: rawAlpha };
            const decl = { Endpoint: { $k: "class", $d: "%s", $fields: [] },
              alpha: { $k: "function", $d: "()->int" } };
            const s1 = $rt.loadHost(raw, decl, "host/cfg", "app.deal", 1, 1);
            const s2 = $rt.loadHost(raw, decl, "host/cfg", "app.deal", 2, 1);
            console.log(JSON.stringify({
              meta: s1.Endpoint === rawEndpoint && s2.Endpoint === rawEndpoint,
              freshNew: s1.Endpoint$new !== s2.Endpoint$new,
              freshFields: s1.Endpoint$fields !== s2.Endpoint$fields,
              sameRead: s1.alpha === s1.alpha,
              crossImport: s1.alpha !== s2.alpha,
              rawFunction: raw.alpha === rawAlpha && s1.alpha !== rawAlpha,
            }));
            """.formatted(endpointId, endpointId));
        NodeRun probe = runNode(out, "surface-probe.js");
        checkEq(0, probe.status(), "the surface probe exits 0: "
            + probe.output());
        check(probe.output().contains("\"meta\":true"),
            "the raw class META reference is preserved, never cloned: "
                + probe.output());
        check(probe.output().contains("\"freshNew\":true"),
            "each source import synthesizes a fresh C$new: "
                + probe.output());
        check(probe.output().contains("\"freshFields\":true"),
            "each source import synthesizes a fresh C$fields: "
                + probe.output());
        check(probe.output().contains("\"sameRead\":true"),
            "repeated reads from one occurrence's surface are identical: "
                + probe.output());
        check(probe.output().contains("\"crossImport\":true"),
            "different source imports' typed functions are unequal: "
                + probe.output());
        check(probe.output().contains("\"rawFunction\":true"),
            "the typed wrapper never replaces or clones the raw function: "
                + probe.output());
    }

    /** The rendered source of one implementation module of an emission. */
    private static String sourceOf(JsProjectEmission emission, String module) {
        return artifactOf(emission, module).source();
    }

    private static void writeOrderedHost(Path out, boolean hasZeta,
            String alpha, String zeta) throws IOException {
        StringBuilder source = new StringBuilder("""
            "use strict";
            const order = [];
            globalThis.__readOrder = order;
            module.exports = {};
            """);
        for (String name : hasZeta ? List.of("zeta", "alpha") : List.of("alpha")) {
            source.append("Object.defineProperty(module.exports, \"").append(name)
                .append("\", { enumerable: true, get: function () { order.push(\"")
                .append(name).append("\"); return ")
                .append(name.equals("alpha") ? alpha : zeta).append("; } });\n");
        }
        writeFileIn(out, "host/cfg.js", source.toString());
    }

    /**
     * The declared-read order and load-time failure drive: the declaration
     * file names {@code zeta} before {@code alpha} and the host file defines
     * its getters in the same z-before-a order, so an ascending read can
     * never be explained by either file's own property order. The recorded
     * declared validation reads {@code [alpha]}/{@code [alpha,zeta]} and
     * stops at the first failure; a declared failure keeps its exact
     * import-located tuple, and a raw non-object result raises the complete
     * unlocated {@code host module did not return a module object} tuple
     * under require and the actual initialization-failure presentation under
     * direct execution.
     */
    private static void testHostDeclaredOrderAndRawShape(Path tmpRoot,
                                                        boolean node)
            throws Exception {
        System.out.println("-- the ascending declared-read order, the"
            + " first-failure stop and the unlocated raw-shape guard --");
        Path root = Files.createTempDirectory(tmpRoot, "host-order");
        writeFileIn(root, "src/cfg.d.deal", """
            export function zeta(): int;
            export function alpha(): int;
            """);
        Path declaration = root.resolve("src/cfg.d.deal").toAbsolutePath();
        Fixture fixture = compileFixture(root, """
            import * as h from "host/cfg";

            export function run(): int {
              return h.alpha() + h.zeta();
            }

            export function main(): null { return null; }
            """, Map.of(), Map.of("host/cfg", declaration.toString()));
        JsProjectEmission emission = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(false));
        String app = sourceOf(emission, "app");
        check(app.indexOf("\"alpha\":") < app.indexOf("\"zeta\":"),
            "the zeta-first declaration still emits the ascending declared"
                + " map alpha, zeta: " + app);
        checkEq(1, countOccurrences(app, "$rt.loadHost("),
            "the single source HOST import emits exactly one validating"
                + " load: " + app);

        Path out = stage(fixture, "host-order", emission);
        if (!node) {
            skip("the declared-read order drive needs node");
            return;
        }
        writeFileIn(out, "driver.js", """
            const $rt = require("./deal/runtime.js");
            const out = {};
            let first = null;
            let second = null;
            let app = null;
            try {
              app = require("./app.js");
            } catch (e) {
              first = e;
            }
            out.state = $rt.moduleState("app");
            out.order = globalThis.__readOrder
              ? globalThis.__readOrder.join(",") : "";
            try {
              require("./app.js");
            } catch (e) {
              second = e;
            }
            out.sticky = first !== null && first === second;
            if (first !== null) {
              out.tuple = {
                code: first.code,
                message: first.message,
                expected: first.expected === undefined
                  ? "<absent>" : first.expected,
                actual: first.actual === undefined ? "<absent>" : first.actual,
                file: first.file === undefined ? "<absent>" : first.file,
                line: first.line === undefined ? "<absent>" : first.line,
                column: first.column === undefined ? "<absent>" : first.column,
              };
            } else {
              out.run = app.run.$f();
            }
            console.log(JSON.stringify(out));
            """);

        // (a) alpha (ascending first) invalid: the exact first-invalid tuple
        // at the import origin, and zeta is never read.
        writeOrderedHost(out, true, "17", "function () { return 9; }");
        NodeRun alphaFailure = runNode(out, "driver.js");
        checkEq(0, alphaFailure.status(), "the alpha-failure driver exits 0: "
            + alphaFailure.output());
        check(alphaFailure.output().contains("\"order\":\"alpha\""),
            "the first invalid export stops later reads (only alpha is"
                + " read): " + alphaFailure.output());
        check(alphaFailure.output().contains("\"tuple\":{\"code\":"
                + "\"E8011\",\"message\":\"host export 'alpha' in module"
                + " 'host/cfg' is not a function\",\"expected\":\"function\","
                + "\"actual\":\"number\",\"file\":"),
            "the first invalid export renders the exact declared-failure"
                + " tuple at the import origin: " + alphaFailure.output());
        check(alphaFailure.output().contains("\"file\":\""
                + root.resolve("src/app.deal") + "\",\"line\":1,"
                + "\"column\":1}"),
            "the declared failure keeps the import declaration's own located"
                + " origin: " + alphaFailure.output());
        check(alphaFailure.output().contains("\"state\":\"FAILED\""),
            "a failed host import leaves the importing implementation in the"
                + " sticky FAILED state: " + alphaFailure.output());

        // (b) alpha valid, zeta invalid: the ascending read visits both and
        // stops at the invalid one.
        writeOrderedHost(out, true, "function () { return 7; }", "17");
        NodeRun zetaFailure = runNode(out, "driver.js");
        checkEq(0, zetaFailure.status(), "the zeta-failure driver exits 0: "
            + zetaFailure.output());
        check(zetaFailure.output().contains("\"order\":\"alpha,zeta\""),
            "the later failure reads alpha, zeta in ascending name order: "
                + zetaFailure.output());
        check(zetaFailure.output().contains("host export 'zeta' in module"
                + " 'host/cfg' is not a function"),
            "the later failure names its own export: "
                + zetaFailure.output());

        // (c) zeta missing: the exact missing-export tuple with absent
        // expected/actual (the arm defines neither) and no later reads.
        writeOrderedHost(out, false, "function () { return 7; }", "");
        NodeRun zetaMissing = runNode(out, "driver.js");
        checkEq(0, zetaMissing.status(), "the missing-export driver exits 0: "
            + zetaMissing.output());
        check(zetaMissing.output().contains("\"order\":\"alpha\""),
            "the missing export stops after the earlier valid read: "
                + zetaMissing.output());
        check(zetaMissing.output().contains("\"code\":\"E8011\",\"message\":"
                + "\"missing host export 'zeta' in module 'host/cfg'\","
                + "\"expected\":\"<absent>\",\"actual\":\"<absent>\","
                + "\"file\":\"")
                && zetaMissing.output().contains("\",\"line\":1,"
                    + "\"column\":1}"),
            "the missing export renders the exact tuple with an absent"
                + " expected/actual pair and the import origin: "
                + zetaMissing.output());

        // (d) the valid control: the same z-a declaration reads both in
        // ascending order and the program runs.
        writeOrderedHost(out, true, "function () { return 7; }",
            "function () { return 9; }");
        NodeRun valid = runNode(out, "driver.js");
        checkEq(0, valid.status(), "the valid control exits 0: "
            + valid.output());
        check(valid.output().contains("\"order\":\"alpha,zeta\""),
            "the valid control reads alpha, zeta in ascending name order: "
                + valid.output());
        check(valid.output().contains("\"run\":16"),
            "the valid control calls both host exports: " + valid.output());
        check(valid.output().contains("\"state\":\"INITIALIZED\""),
            "a successful host import initializes its implementation: "
                + valid.output());

        // (e) the raw non-object result: the complete unlocated tuple under
        // require (never a module clause or a guessed/null origin), the
        // sticky failure, and no partial published surface.
        writeFileIn(out, "host/cfg.js", "module.exports = 17;\n");
        NodeRun rawShape = runNode(out, "driver.js");
        checkEq(0, rawShape.status(), "the raw-shape driver exits 0: "
            + rawShape.output());
        check(rawShape.output().contains("\"state\":\"FAILED\""),
            "a raw non-object host result fails the importing"
                + " implementation: " + rawShape.output());
        check(rawShape.output().contains("\"tuple\":{\"code\":\"E8011\","
                + "\"message\":\"host module did not return a module object\","
                + "\"expected\":\"table\",\"actual\":\"number\","
                + "\"file\":\"<absent>\",\"line\":\"<absent>\","
                + "\"column\":\"<absent>\"}"),
            "the raw non-object guard renders the complete unlocated tuple"
                + " with the JS diagnostic kind: " + rawShape.output());
        check(rawShape.output().contains("\"sticky\":true"),
            "a later load rethrows the identical saved failure without"
                + " retrying the raw initialization: " + rawShape.output());
        writeFileIn(out, "surface.js", """
            const $rt = require("./deal/runtime.js");
            let failure = null;
            try { require("./app.js"); } catch (e) { failure = e; }
            console.log(JSON.stringify({
              preserved: failure !== null,
              exports: Object.keys($rt.moduleSurface("app")).length,
            }));
            """);
        NodeRun noPartial = runNode(out, "surface.js");
        checkEq(0, noPartial.status(), "the partial-surface driver exits 0: "
            + noPartial.output());
        check(noPartial.output().contains("\"exports\":0"),
            "a failed initialization publishes no partial export surface: "
                + noPartial.output());
        NodeRun direct = runNode(out, entryPath(out));
        checkEq(1, direct.status(),
            "direct execution of a failing host initialization exits 1: "
                + direct.output());
        check(direct.output().contains("host module did not return a module"
                + " object"),
            "direct execution keeps the initialization failure's own"
                + " presentation: " + direct.output());
        check(!direct.output().contains("DEAL_ERROR_CODE"),
            "direct execution never converts an initialization failure to"
                + " main-shim framing: " + direct.output());
        check(!direct.output().contains(" in module '"),
            "the raw-shape arm names no module clause: " + direct.output());
    }

    /**
     * The STDLIB drive: the deployed (project-overridable) stdlib modules
     * behind the emitted relative require, the once-only surface identity
     * across direct/stored/namespace calls, the separate console streams,
     * the number-returning clock and the canonical JSON projections.
     */
    private static void testStdlibSurfaceAndCalls(Path tmpRoot, boolean node)
            throws Exception {
        System.out.println("-- STDLIB: the once-only deployed surface, the"
            + " cataloged calls, separate streams, number time and canonical"
            + " JSON --");
        Path root = Files.createTempDirectory(tmpRoot, "stdlib");
        Fixture fixture = compileFixture(root, """
            import * as console from "std/console";
            import * as json from "std/json";
            import * as str from "std/string";
            import * as time from "std/time";

            export function checks(): string {
              console.log("out-line");
              console.error("err-line");
              let parse: (s: string) => table = json.parse;
              if (json.parse !== parse) {
                throw { code: "STDLIB_IDENTITY", message: "surface" };
              }
              let stored: table = parse("{\\"n\\":1}");
              let direct: table = json.parse("{\\"n\\":2}");
              let a: int = stored.n;
              let b: int = direct.n;
              if (a !== 1 || b !== 2) {
                throw { code: "STDLIB_CALL", message: "values" };
              }
              let text: string = json.stringify(stored);
              if (text !== "{\\"n\\":1}") {
                throw { code: "STDLIB_JSON", message: text };
              }
              let now: number = time.nowMillis();
              if (now <= 0.0) {
                throw { code: "STDLIB_TIME", message: "clock" };
              }
              let length: int = str.length("héllo");
              if (length !== 5) {
                throw { code: "STDLIB_STRING", message: "length" };
              }
              return "ok";
            }

            export function duplicateKey(): table {
              return json.parse("{\\"a\\":1,\\"a\\":2}");
            }

            export function main(): null { return null; }
            """);
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String app = sourceOf(emission, "app");
        check(app.contains("$require(\"./std/console\")")
                && app.contains("$require(\"./std/json\")")
                && app.contains("$require(\"./std/string\")")
                && app.contains("$require(\"./std/time\")"),
            "every stdlib import requires the deployed module by its"
                + " relative specifier (never an ambient module lookup): "
                + app);
        checkEq(1, countOccurrences(app, "$require(\"./std/json\")"),
            "one recorded stdlib import is one execution registration: "
                + app);
        check(app.contains("$d") && app.contains("[\"parse\"].$f("),
            "the cataloged direct call invokes the deployed module's typed"
                + " entry: " + app);

        Path out = stage(fixture, "stdlib", emission);
        if (!node) {
            skip("the STDLIB drive needs node");
            return;
        }
        writeFileIn(out, "driver.js", """
            const app = require("./app.js");
            const out = { checks: app.checks.$f() };
            try {
              app.duplicateKey.$f();
            } catch (e) {
              out.duplicate = { code: e.code, message: e.message,
                expected: e.expected === undefined ? "<absent>" : e.expected,
                actual: e.actual === undefined ? "<absent>" : e.actual };
            }
            console.log(JSON.stringify(out));
            """);
        NodeStreams run = runNodeStreams(out, "driver.js");
        checkEq(0, run.status(), "the stdlib driver exits 0: " + run.stdout()
            + run.stderr());
        check(run.stdout().contains("out-line"),
            "console.log writes the captured stdout stream: " + run.stdout());
        check(!run.stdout().contains("err-line"),
            "console.error never writes the stdout stream: " + run.stdout());
        check(run.stderr().contains("err-line"),
            "console.error writes the captured stderr stream: " + run.stderr());
        check(!run.stderr().contains("out-line"),
            "console.log never writes the stderr stream: " + run.stderr());
        check(run.stdout().contains("\"checks\":\"ok\""),
            "the stdlib identity, direct/stored JSON calls, number clock and"
                + " Unicode-scalar string row all run: " + run.stdout());
        check(run.stdout().contains("\"duplicate\":{\"code\":\"E8001\","
                + "\"message\":\"JSON parse error at position 8: duplicate"
                + " key\""),
            "the deployed JSON scanner raises its canonical duplicate-key"
                + " projection: " + run.stdout());

        // The deployed copy owns the algorithm: an override staged at the
        // published stdlib path is what the emitted require resolves.
        writeFileIn(out, "std/console.js", """
            "use strict";
            const $rt = require("../deal/runtime");
            const $console = {};
            $console.log = $rt.function("(string)->null",
              function (x, $file, $line, $column) {
                $rt.checkString(x, $file, $line, $column);
                process.stdout.write("[override] " + x + "\\n");
                return $rt.checkNull(null, $file, $line, $column);
              });
            $console.error = $rt.function("(string)->null",
              function (x, $file, $line, $column) {
                $rt.checkString(x, $file, $line, $column);
                process.stderr.write("[override] " + x + "\\n");
                return $rt.checkNull(null, $file, $line, $column);
              });
            module.exports = $console;
            """);
        NodeStreams overridden = runNodeStreams(out, "driver.js");
        checkEq(0, overridden.status(), "the overridden stdlib driver exits"
            + " 0: " + overridden.stdout() + overridden.stderr());
        check(overridden.stdout().contains("[override] out-line"),
            "the emitted require resolves the deployed stdlib copy, so a"
                + " project override owns the algorithm: "
                + overridden.stdout());
        check(overridden.stderr().contains("[override] err-line"),
            "the overridden stderr row keeps its own stream: "
                + overridden.stderr());
    }

    // =========================================================================
    // 7. The structural declaration projection
    // =========================================================================

    private static void testDeclarationProjection(Path tmpRoot)
            throws Exception {
        System.out.println("-- the structural declaration projection:"
            + " name-sorted exports, declaration-ordered fields --");
        Path root = Files.createTempDirectory(tmpRoot, "declarations");
        Path declaration = root.resolve("src/cfg.d.deal").toAbsolutePath();
        writeFileIn(root, "src/cfg.d.deal", """
            export class Endpoint {
              path: string = "";
              port?: int;
              tag: string = "t";
              weight: int | null = null;
            }

            export function zebra(): int;
            export function alpha(x: string): string;
            """);
        Fixture fixture = compileFixture(root, """
            import * as cfg from "host/cfg";

            export function main(): null {
              return null;
            }
            """, Map.of(), Map.of("host/cfg", declaration.toString()));

        RuntimeDeclarationSurface surface = RuntimeDeclarationProjection
            .project(fixture.surface(), fixture.identities(),
                fixture.seeds());
        checkEq(1, surface.modules().size(),
            "the declaration surface carries exactly the declaration module"
                + " of the compilation: " + surface.modules().keySet());
        ModuleId cfg = surface.modules().keySet().iterator().next();
        RuntimeDeclarationSurface.RuntimeDeclarationModule module =
            surface.modules().get(cfg);
        check(module != null, "the declaration module projects its surface");
        checkEq(RuntimeDeclarationSurface.DeclarationKind.HOST, module.kind(),
            "the declaration kind is the structural pair's HOST member");
        checkEq(List.of("Endpoint", "alpha", "zebra"),
            List.copyOf(module.exports().keySet()),
            "declared exports (declared classes included) carry the ascending"
                + " Java String order");
        checkEq("(string)->string", module.exports().get("alpha")
                .canonicalSpecText(),
            "the export descriptor is the structural declaration fact");
        checkEq("()->int", module.exports().get("zebra").canonicalSpecText(),
            "the export descriptor is the structural declaration fact");
        RuntimeDeclarationSurface.RuntimeDeclaredClass endpoint =
            module.classes().get("Endpoint");
        check(endpoint != null, "the declared class projects its identity");
        checkEq(List.of("path", "port", "tag", "weight"),
            endpoint.fields().stream()
                .map(RuntimeDeclarationSurface.RuntimeDeclaredField::name)
                .toList(),
            "class fields keep declaration order");
        checkEq(List.of(true, false, true, true),
            endpoint.fields().stream()
                .map(RuntimeDeclarationSurface.RuntimeDeclaredField::required)
                .toList(),
            "the required flag is the projected !optional fact");
        checkEq(List.of(true, false, true, true),
            endpoint.fields().stream()
                .map(RuntimeDeclarationSurface.RuntimeDeclaredField::hasDefault)
                .toList(),
            "the has-default flag is the projected declaration fact");
        checkEq(List.of("string", "int", "string", "?int"),
            endpoint.fields().stream()
                .map(f -> f.descriptor().canonicalSpecText())
                .toList(),
            "the field descriptors are the structural descriptors"
                + " (nullability included, optionality carried by required)");

        // The host import renders from the projected surface: the emitted
        // load carries exactly this declaration module's ascending-name
        // declared map, so the renderer consumes the one projection and
        // never re-derives a name or an order.
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        String app = sourceOf(emission, "app");
        check(app.contains("\"alpha\": { $k: \"function\", $d:"
                + " \"(string)->string\" }")
                && app.contains("\"zebra\": { $k: \"function\", $d:"
                + " \"()->int\" }")
                && app.indexOf("\"Endpoint\":") < app.indexOf("\"alpha\":"),
            "the emitted declared map is the projected ascending-name"
                + " surface: " + app);
        checkEq(1, countOccurrences(app, "$rt.loadHost("),
            "the declaration module's one source import emits one validating"
                + " load: " + app);
    }

    // =========================================================================
    // 6. The seam purity and the fail-closed unsupported slice
    // =========================================================================

    private static void testFailClosedAndSeamPurity(Path tmpRoot)
            throws Exception {
        System.out.println("-- no frontend objects on the seam, invalid"
            + " shapes fail closed with the offending origin --");
        for (RecordComponent component
                : JsEmissionInput.class.getRecordComponents()) {
            String type = component.getType().getName();
            check(type.startsWith("deal.semantic.ir.")
                    || type.startsWith("deal.semantic.")
                    || type.startsWith("deal.codegen.js.")
                    || type.equals("boolean")
                    || type.startsWith("java.util."),
                "the rendering input's " + component.getName()
                    + " carries no frontend object (" + type + ")");
        }
        for (RecordComponent component
                : JsModuleArtifact.class.getRecordComponents()) {
            String type = component.getType().getName();
            check(type.startsWith("deal.semantic.ir.")
                    || type.startsWith("deal.codegen.")
                    || type.equals("java.lang.String")
                    || type.startsWith("java.util."),
                "a module artifact's " + component.getName()
                    + " carries no frontend object (" + type + ")");
        }

        // The async slice is realized (ISSUE-0800): the same program that
        // the foundation once rejected now renders the native async body
        // and the single await. The HOST/STDLIB boundary is realized too
        // (ISSUE-0801): a malformed declaration link still fails closed
        // with the offending import's own origin and no partial result.
        Path root = Files.createTempDirectory(tmpRoot, "failclosed");
        Fixture fixture = compileFixture(root, """
            async function work(): int {
              return 1;
            }

            export async function run(): int {
              let v: int = await work();
              return v;
            }

            export function main(): null {
              return null;
            }
            """);
        JsProjectEmission asyncEmission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        passed++;
        String asyncSource = asyncEmission.modules().get(0).source();
        check(asyncSource.contains("async function "),
            "the declared async body is the native async function");
        check(asyncSource.contains("await "),
            "the awaited operation is the native await");

        // The malformed input shape is rejected before any rendering.
        try {
            new JsEmissionInput(fixture.project(), Map.of(),
                fixture.registries(), fixture.seeds(), fixture.namespaces(),
                RuntimeDeclarationProjection.project(fixture.surface(),
                    fixture.identities(), fixture.seeds()),
                fixture.emissionInput(false).sourceFiles(), false);
            check(false, "a table-less emission input is rejected");
        } catch (IllegalArgumentException expected) {
            passed++;
            check(expected.getMessage().contains("block-membership table"),
                "the rejection names the missing produced table: "
                    + expected.getMessage());
        }
        try {
            new JsProjectEmission("app.js", List.of());
            check(false, "an empty project emission is rejected");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        try {
            new JsProjectEmission("../escape.js", List.of());
            check(false, "an escaping project emission path is rejected");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the temp tree.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of the temp tree.
        }
    }
}
