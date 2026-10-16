package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.SharedEmissionFailure;
import deal.codegen.SourceMapGenerator;
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
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.NamespaceRegistrations;
import deal.semantic.ir.RuntimeDescriptor;
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
 * the JSON walkers), and the ISSUE-0800 call/boundary realization (native
 * {@code ASYNC_START}/{@code AWAIT} under real Promises, the async adapter
 * and cross-module async entry, the direct-run/require entry split with
 * the uncaught-main presentation, the {@code $rt.invokeAsyncExport} legs
 * with their exact per-invocation counts, the awaited failure transport
 * across modules, and the host-driven callback crossing).
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
            testValuesContainersAndControl(tmpRoot, node);
            testNullableEqualityNilEquivalence(tmpRoot, node);
            testCellsClosuresAndAdapters(tmpRoot, node);
            testIntrinsicValueCarriers(tmpRoot, node);
            testArrayHoleReadDecision(tmpRoot, node);
            testAdapterCallOrigin(tmpRoot, node);
            testModuleNamespaceIndexCarrier(tmpRoot, node);
            testFailureTuples(tmpRoot, node);
            testClassConstructionAndJson(tmpRoot, node);
            testModuleRegistry(tmpRoot, node);
            testAsyncCallsAndCompletion(tmpRoot, node);
            testAwaitedFailureTransport(tmpRoot, node);
            testAsyncExportAbiAndEntryLegs(tmpRoot, node);
            testCallbackCrossing(tmpRoot, node);
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
            Map<ModuleId, JsSourceFile> sourceFiles = new LinkedHashMap<>();
            for (CheckedModuleInput module : checkedProject.modules()) {
                if (module.kind() != deal.semantic.CheckedModuleKind
                        .IMPLEMENTATION) {
                    continue;
                }
                sourceFiles.put(module.moduleId(), new JsSourceFile(
                    module.sourceId(), "src/" + displayPath(root, module)));
            }
            return new JsEmissionInput(project, tables, registries, seeds,
                namespaces, RuntimeDeclarationProjection.project(surface,
                    identities, seeds), sourceFiles, sourceMap);
        }
    }

    private static String displayPath(Path root, CheckedModuleInput module) {
        try {
            return root.relativize(module.sourcePath()).toString()
                .replace('\\', '/');
        } catch (IllegalArgumentException outsideRoot) {
            return module.moduleId().path() + ".deal";
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
        writeFileIn(root, "src/app.deal", appSource);
        for (Map.Entry<String, String> extra : extraSources.entrySet()) {
            writeFileIn(root, extra.getKey(), extra.getValue());
        }
        Path entry = root.resolve("src/app.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry, output, false, false, false, false, Backend.LUAJIT,
            externals, List.of(root.resolve("src").toAbsolutePath()),
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
                IdentityTestFixtures.moduleIdentityOf(
                    declarationModule.path()));
        }
        BuiltinErrorDeclaration builtinError =
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span());
        SemanticLowerer.ProjectLoweringResult lowering =
            SemanticLowerer.lowerProject(invocation(), built.input(),
                built.index(), manifests.manifests(),
                orchestrator.hostDeclarationSurface(), identities, externC,
                builtinError,
                List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT,
                    IntrinsicKind.BYTES_NEW),
                callbackExports);
        if (lowering.hasErrors() || lowering.project() == null) {
            throw new IllegalStateException("the fixture project did not"
                + " lower: " + lowering.diagnostics());
        }
        ClassRegistrationSeeds.Production seedProduction =
            ClassRegistrationSeeds.produce(orchestrator.hostDeclarationSurface(),
                identities, externC, builtinError);
        if (seedProduction.hasErrors()) {
            throw new IllegalStateException("the fixture project produced no"
                + " class registration seeds: " + seedProduction.diagnostics());
        }
        NamespaceRegistrations.Assembly assembly = NamespaceRegistrations
            .assemble(List.copyOf(lowering.project().modules().values()));
        if (assembly.hasErrors()) {
            throw new IllegalStateException("the fixture project produced no"
                + " namespace registrations: " + assembly.diagnostics());
        }
        return new Fixture(root, built.input(), lowering.project(),
            lowering.tables(), lowering.registries(), seedProduction.seeds(),
            assembly.registrations(), orchestrator.hostDeclarationSurface(),
            identities);
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
     * runtime copy) into a fresh output directory under {@code root}. The
     * renderer itself writes nothing; the drive performs the staging.
     */
    private static Path stage(Fixture fixture, String name, boolean sourceMap)
            throws IOException {
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(sourceMap));
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
        Files.writeString(out.resolve("entry.txt"),
            emission.entryArtifactRelativePath(), StandardCharsets.UTF_8);
        return out;
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
            "\\$s\\d+ = \\(\\(\\$s\\d+ === \\$s\\d+\\) \\|\\| "
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
        Path out = stage(fixture, "intrinsic", false);
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
        Path out = stage(nullable, "hole", false);
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
        String artifact = JsSemanticEmitter.emitProductionProject(
            fixture.emissionInput(false)).modules().get(0).source();
        check(artifact.contains(
                "$rt.checkFunctionSig(\"(number)->int\", $src.$sig, $src,"
                    + " $file, $line, $column)"),
            "the adapter's source-signature check runs at the received call"
                + " span");
        check(artifact.contains(
                "return $src.$f($p0, $file, $line, $column);"),
            "the adapter forwards the received span triple to the source"
                + " wrapper");
        Path out = stage(fixture, "adapter-origin", false);
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
        String artifact = emission.modules().stream()
            .filter(a -> a.artifactRelativePath().equals("app.js"))
            .findFirst().orElseThrow().source();
        check(artifact.contains("$rt.tableSet(")
                && artifact.contains("$rt.tableGet("),
            "the namespace index arms use the carrier-neutral table members");
        check(!artifact.contains(".set(") && !artifact.contains(".get(")
                && !artifact.contains(".delete("),
            "no Map member call is emitted for the plain-object namespace"
                + " surface: " + artifact.lines()
                    .filter(l -> l.contains(".set(") || l.contains(".get("))
                    .findFirst().orElse(""));
        Path out = stage(fixture, "namespace", false);
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
            if (lines[i].matches("\\s*\\$s\\d+ = \\$s\\d+\\[\\$s\\d+\\];")) {
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
     * The composed class drive (ISSUE-0799): one legal module-class project
     * exercised through real Node artifacts — fresh and re-executed defaults,
     * provided/optional skips, retained references, imported provider
     * construction, field read/write/delete/has, the public helper ABI
     * (META, full-construction C$new, hidden C$fields and the generated
     * wrappers) and the JSON roundtrip with its specified null failures.
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

            // @jsonable
            export class Form {
              name: string = "";
              nick?: string;
              score: number = 0.0;
              point: lib.Point = {};
              many: lib.Point[] = [];
            }

            export function main(): null {
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
                }
                """));

        String libSource = null;
        String appSource = null;
        JsProjectEmission emission =
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
        for (JsModuleArtifact artifact : emission.modules()) {
            if (artifact.artifactRelativePath().equals("lib.js")) {
                libSource = artifact.source();
            }
            if (artifact.artifactRelativePath().equals("app.js")) {
                appSource = artifact.source();
            }
        }
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
        Path out = stage(fixture, "classes", false);
        Files.writeString(out.resolve("driver.js"), """
            const app = require("./app.js");
            const lib = require("./lib.js");
            const rt = require("./deal/runtime.js");
            const out = {state: rt.moduleState("app"),
              exports: Object.keys(app).sort().join(","),
              meta: app.Form, fields: app.Form$fields,
              libExports: Object.keys(lib).sort().join(",")};
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
            """);
        NodeRun run = runNode(out, "driver.js");
        checkEq(0, run.status(), "the class driver exits 0: " + run.output());
        check(run.output().contains("\"state\":\"INITIALIZED\""),
            "the entry module's init completed: " + run.output());
        check(run.output().contains(
                "\"exports\":\"Form,Form$fields,Form$fromJson,Form$new,Form$toJson,main\""),
            "the module surface carries the class metadata, the constructor,"
                + " the hidden descriptor export and the generated wrappers"
                + " alongside the declared exports: " + run.output());
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
                "$rt.function(\"async()->int\", async () => {"),
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

    // =========================================================================
    // 5. The structural declaration projection
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

        // The host import slice is not realized by this foundation: the
        // renderer fails closed with the import's own origin and produces no
        // partial result.
        try {
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
            check(false, "the host import slice fails closed");
        } catch (SharedEmissionFailure failure) {
            passed++;
            check(failure.diagnosticRange() != null,
                "the fail-closed host import failure carries the offending"
                    + " operation's own origin");
            check(failure.getMessage().contains("HOST"),
                "the failure names the unsupported slice: "
                    + failure.getMessage());
        }
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
        try {
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
            check(false, "the async-operation slice fails closed");
        } catch (SharedEmissionFailure failure) {
            passed++;
            check(failure.diagnosticRange() != null,
                "the async fail-closed failure carries the offending"
                    + " operation's own origin");
            check(failure.getMessage().contains("ASYNC_START"),
                "the failure names the unsupported op and slice: "
                    + failure.getMessage());
        }

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
