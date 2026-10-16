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
 * The JavaScript semantic-IR renderer foundation acceptance drives
 * (ISSUE-0798): one artifact per implementation unit in dependency order,
 * the structural declaration projection, local value/cell/control
 * execution under the real Node binary, the nullable equality pair's
 * shared nil-equivalence rule, the private module registry's repeat
 * identity and sticky failure, coordinate-only maps, the
 * malformed/no-frontend seam assertions, independent determinism, and the
 * fail-closed unsupported-slice contract with the offending operation's own
 * origin.
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
            testFailureTuples(tmpRoot, node);
            testModuleRegistry(tmpRoot, node);
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

    /**
     * Compiles one fixture project through the production frontend, lowers
     * it once, and produces the seeds/namespaces/declaration surface of the
     * canonical rendering input.
     */
    private static Fixture compileFixture(Path root, String appSource,
            Map<String, String> extraSources,
            Map<String, String> externals) throws Exception {
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
                Set.of());
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
    // 3. Failure tuples, throw transport, and the entry presentation
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
            class Point {
              x: int = 0;
              y: int = 0;
            }

            export function main(): null {
              let p: Point = {x: 1, y: 2};
              return null;
            }
            """);
        try {
            JsSemanticEmitter.emitProductionProject(fixture.emissionInput(false));
            check(false, "the declared-class construction slice fails closed");
        } catch (SharedEmissionFailure failure) {
            passed++;
            check(failure.diagnosticRange() != null,
                "the declared-class fail-closed failure carries the"
                    + " construction's own origin");
            check(failure.getMessage().contains("CLASS_NEW"),
                "the failure names the unsupported op: "
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
