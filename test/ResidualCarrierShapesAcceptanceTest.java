package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.module.ProductionProjectEmission;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ClassFactoryRegistry;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.ModuleId;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticIrValidator;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.SemanticProfile;
import deal.semantic.ir.SourceOriginKind;
import deal.semantic.ir.StructuredBodyTable;
import deal.semantic.ir.ValueId;
import deal.test.conformance.SidecarExpectations;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ISSUE-0715: the registered residual-carrier union acceptance drive
 * ({@code residual-carrier-shapes-production-realization} D1 and the
 * acceptance; {@code dispatched-corpus-production-realization} R4;
 * {@code luajit-jvm-single-lowering-production-cutover} C1/C2/C7/C8;
 * {@code dispatched-corpus-production-acceptance} A1-A4): the eleven
 * residual fixtures this sub-epic owns and the two dual-mechanism bytes
 * fixtures compile and execute through the release-owned production
 * invocation on LuaJIT and JVM with zero E6005
 * {@code CONSTRUCT_UNLOWERED}/{@code RETAINED_ABI_DEFERRED}/
 * {@code SHARED_EMITTER_COVERAGE}, stage one project artifact with no
 * retained emission, execute on the real toolchains with their pinned
 * {@code runtime-ok} sidecar outcome, and agree with the semantic oracle
 * event-for-event. The two dual-mechanism fixtures are accepted jointly
 * with the landed BYTES realization.
 *
 * <ol>
 *   <li><b>The inventory and the pins.</b> The thirteen named fixtures
 *       and their sidecars are present; every sidecar pins
 *       {@code runtime-ok}, exit 0, and both empty transcripts on the
 *       three lanes under the unchanged schema.</li>
 *   <li><b>The union drive.</b> For every fixture and both targets: the
 *       release-owned production invocation compiles with zero
 *       diagnostics, stages exactly one project artifact with no retained
 *       emission, emits byte-identical artifact bytes on a repeated
 *       compile, and executes the staged artifact on the real toolchain
 *       (the fixture's exported probe called directly for the sync and
 *       {@code main} probes and through the recorded async dispatch entry
 *       for the async probes) with the sidecar-pinned outcome; the oracle
 *       drives the same probes event-for-event through the differential
 *       matrix with no divergence.</li>
 *   <li><b>The dual-mechanism joint acceptance.</b> The two bytes
 *       fixtures carry the nested declared bodies and the nested async
 *       declarations the residual mechanisms own <em>and</em> the landed
 *       bytes element contract in the same lowered unit; a broken bytes
 *       realization or a broken residual mechanism fails the same
 *       drive.</li>
 *   <li><b>The represented-tail monotonicity.</b> The two tail fixtures
 *       and a unit-level both-branch-return body with a following
 *       unreachable statement keep their body block non-{@code OPEN}
 *       while the walk continues: the tail is a member of the block after
 *       its terminator, no implicit return is fabricated, only the Lua
 *       artifact emits it (the JVM artifact keeps the reachability skip
 *       and compiles under {@code javac --release 25 -proc:none}), and it
 *       never executes.</li>
 *   <li><b>The union invariants.</b> The sidecars and fixture sources are
 *       read, never written; the corpus count pin, the guard identifiers,
 *       the retargeted control-flow pin files, and this test's foreground
 *       registration stay landed.</li>
 * </ol>
 */
public final class ResidualCarrierShapesAcceptanceTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            fail(message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        if (java.util.Objects.equals(expected, actual)) {
            passed++;
        } else {
            fail(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // The thirteen named fixtures
    // =========================================================================

    private static final Path CORPUS =
        Path.of("test", "conformance", "backend-runtime");

    /** How the fixture's exported probe is exercised. */
    private enum ProbeKind {
        /** The fixture's {@code main} is the probe (the deferred entry runs it). */
        MAIN,
        /** A synchronous exported zero-arity probe. */
        SYNC,
        /** An async exported zero-arity probe (the recorded async dispatch entry). */
        ASYNC
    }

    /**
     * One named fixture: the corpus-relative path, its exported zero-arity
     * probe, how the probe is driven, the pinned probe value as the DEAL
     * expression the sync driver asserts, and the pinned value text every
     * consumer compares against.
     */
    private record Fixture(String relativePath, String probe, ProbeKind kind,
                           String pinnedDeal, String pinnedText, String pinnedAtom) {

        String moduleKey() {
            return relativePath.replace('/', '.');
        }

        String what() {
            return relativePath;
        }
    }

    private static final List<Fixture> FIXTURES = List.of(
        new Fixture("functions/direct-recursion", "test_direct_recursion",
            ProbeKind.SYNC, "0", "0", "int:0"),
        new Fixture("functions/nested-scope-recursion", "test_nested_scope_recursion",
            ProbeKind.SYNC, "0", "0", "int:0"),
        new Fixture("async-await/async-await-statement", "f", ProbeKind.ASYNC,
            null, "0", "int:0"),
        new Fixture("async-await/async-error-propagation", "outer", ProbeKind.ASYNC,
            null, "E_INNER", "str:E_INNER"),
        new Fixture("async-await/async-if-branching", "f", ProbeKind.ASYNC,
            null, "10", "int:10"),
        new Fixture("async-await/async-throw-catch", "f", ProbeKind.ASYNC,
            null, "E_TEST", "str:E_TEST"),
        new Fixture("control-flow/return-in-try", "test_return_in_try_loop",
            ProbeKind.SYNC, "5", "5", "int:5"),
        new Fixture("control-flow/return-inside-try", "test_return_inside_try",
            ProbeKind.SYNC, "7", "7", "int:7"),
        new Fixture("arithmetic/int32-div-rem-boundaries", "main", ProbeKind.MAIN,
            null, "null", "null"),
        new Fixture("descriptors/canonical-class-atom-error-roundtrip",
            "test_canonical_error_atom_roundtrip", ProbeKind.SYNC,
            "\"canonical error atom ok\"", "canonical error atom ok",
            "str:canonical error atom ok"),
        new Fixture("error-handling/error-roundtrip", "test_error_roundtrip",
            ProbeKind.SYNC, "\"error roundtrip ok\"", "error roundtrip ok",
            "str:error roundtrip ok"),
        new Fixture("bytes/bytes-boundary-order", "main", ProbeKind.MAIN,
            null, "null", "null"),
        new Fixture("bytes/bytes-async-closure", "test_bytes_async_closure",
            ProbeKind.ASYNC, null, "0", "int:0"));

    /** The two dual-mechanism fixtures (the joint acceptance with landed BYTES). */
    private static final Set<String> DUAL_MECHANISM = Set.of(
        "bytes/bytes-boundary-order", "bytes/bytes-async-closure");

    /** The two represented-tail fixtures ({@code ...realization} D2). */
    private static final Set<String> TAIL_FIXTURES = Set.of(
        "async-await/async-error-propagation", "async-await/async-throw-catch");

    /**
     * The two recursion fixtures: their traces legitimately re-enter the
     * same {@code CALL} op for each recursive invocation, which the
     * differential harness's non-reentrancy pairing sanity rule records
     * as a notice (it is not a consumer divergence).
     */
    private static final Set<String> RECURSIVE_FIXTURES = Set.of(
        "functions/direct-recursion", "functions/nested-scope-recursion");

    private static Fixture fixture(String relativePath) {
        for (Fixture fixture : FIXTURES) {
            if (fixture.relativePath().equals(relativePath)) {
                return fixture;
            }
        }
        throw new IllegalArgumentException("not a named fixture: " + relativePath);
    }

    private static String fixtureSource(Fixture fixture) throws Exception {
        return ConformanceHarnessMetadata.stripClassificationHeaders(
            Files.readString(CORPUS.resolve(fixture.relativePath() + ".deal"),
                StandardCharsets.UTF_8));
    }

    private static Path sidecarPath(Fixture fixture) {
        return CORPUS.resolve(fixture.relativePath() + ".expect.json");
    }

    // =========================================================================
    // 1. The inventory and the sidecar pins
    // =========================================================================

    private static void testInventoryAndPins() throws Exception {
        System.out.println("-- the thirteen named fixtures and their sidecar pins --");
        checkEq(13, FIXTURES.size(), "the drive enumerates all thirteen named fixtures");
        for (Fixture fixture : FIXTURES) {
            Path source = CORPUS.resolve(fixture.relativePath() + ".deal");
            check(Files.exists(source), fixture.what() + ": the fixture is present");
            check(Files.exists(sidecarPath(fixture)),
                fixture.what() + ": the sidecar is present");
            if (!Files.exists(sidecarPath(fixture))) {
                continue;
            }
            SidecarExpectations.StructuredExpectationSidecar parsed =
                SidecarExpectations.StructuredExpectationSidecar.parse(
                    Files.readString(sidecarPath(fixture), StandardCharsets.UTF_8));
            for (String backend : List.of("luajit", "jvm", "js")) {
                check(parsed.byBackend().containsKey(backend), fixture.what()
                    + ": the sidecar pins the '" + backend + "' lane");
                SidecarExpectations.RuntimeExpectation expectation =
                    parsed.expectationFor(backend);
                if (!(expectation
                        instanceof SidecarExpectations.RuntimeExpectation.Executed executed)) {
                    fail(fixture.what() + ": the " + backend
                        + " leg is an executed expectation");
                    continue;
                }
                checkEq("runtime-ok", executed.mode(),
                    fixture.what() + " (" + backend + "): the pinned mode");
                checkEq(0, executed.exitCode(),
                    fixture.what() + " (" + backend + "): the pinned exit code");
                checkEq("", new String(executed.stdout(), StandardCharsets.UTF_8),
                    fixture.what() + " (" + backend + "): the pinned stdout");
                checkEq("", new String(executed.stderr(), StandardCharsets.UTF_8),
                    fixture.what() + " (" + backend + "): the pinned stderr");
                check(executed.error() == null,
                    fixture.what() + " (" + backend
                        + "): a runtime-ok fixture pins no error snapshot");
            }
        }
        // The two dual-mechanism fixtures carry the shapes the joint
        // acceptance binds: the nested declared bodies of the write's
        // operands, and the nested async declaration awaited in place.
        String boundary = fixtureSource(fixture("bytes/bytes-boundary-order"));
        check(boundary.contains("function target(): bytes")
                && boundary.contains("function index(): int")
                && boundary.contains("function value(): int")
                && boundary.contains("target()[index()] = value()"),
            "bytes-boundary-order: the nested declared operands of the write "
                + "are the residual shape the fixture carries");
        String closure = fixtureSource(fixture("bytes/bytes-async-closure"));
        check(closure.contains("async function counter(b: bytes): bytes")
                && closure.contains("await counter(bytes(1))"),
            "bytes-async-closure: the nested async declaration awaited in place "
                + "is the residual shape the fixture carries");
        // Every exported function of every named fixture executes in the
        // drive: the fixture's own main (through the deferred entry) and the
        // named probe.
        for (Fixture fixture : FIXTURES) {
            List<String> exports = exportedFunctions(fixtureSource(fixture));
            check(exports.contains(fixture.probe()), fixture.what() + ": the named "
                + "probe '" + fixture.probe() + "' is one of the fixture's exports: "
                + exports);
            for (String export : exports) {
                check("main".equals(export) || export.equals(fixture.probe()),
                    fixture.what() + ": every exported function executes in the "
                        + "drive — '" + export + "' is outside {main, "
                        + fixture.probe() + "}");
            }
        }
    }

    /** The exported function names of one fixture source, in source order. */
    private static List<String> exportedFunctions(String source) {
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "(?m)^export (?:async )?function ([A-Za-z0-9_]+)").matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    // =========================================================================
    // 2. The production drive harness
    // =========================================================================

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    /** One executed process with both captured streams. */
    private static record ProcessOutcome(int exitCode, String stdout, String stderr) {
    }

    /** One staged production compile and its repeated-compile twin. */
    private record Compiled(Path root, CompilationOrchestrator orchestrator,
                            Path artifact, byte[] artifactBytes, byte[] repeatBytes) {

        Path outputRoot() {
            return root.resolve("out");
        }
    }

    /** One lowered closure over a compiled fixture. */
    private record Lowered(ExecutableLoweredProject project,
                           Map<ModuleId, StructuredBodyTable> tables,
                           Map<ModuleId, ClassFactoryRegistry> registries,
                           ModuleId fixtureModule, LoweredModuleUnit unit) {

        List<SemanticOp> opsOfBlock(BlockId block) {
            List<SemanticOp> ops = new ArrayList<>();
            List<OpId> members = tables.get(fixtureModule).blockOps().get(block);
            if (members == null) {
                return ops;
            }
            for (OpId id : members) {
                for (SemanticOp op : unit.ops()) {
                    if (op.opId().equals(id)) {
                        ops.add(op);
                    }
                }
            }
            return ops;
        }
    }

    /** One driven fixture and target. */
    private record Outcome(Fixture fixture, Backend backend, Compiled compiled,
                           Lowered lowered) {
    }

    /**
     * Materializes one fixture (classification headers stripped) as the
     * entry module of a temp project, compiles it through the release-owned
     * production invocation, and compiles it a second time from the same
     * input for the byte-identity comparison.
     */
    private static Compiled compileStaged(Fixture fixture, Backend backend, Path root)
            throws Exception {
        Path source = root.resolve("src").resolve(fixture.relativePath() + ".deal");
        Files.createDirectories(source.getParent());
        Files.writeString(source, fixtureSource(fixture), StandardCharsets.UTF_8);
        CompilationOrchestrator orchestrator = compile(source, root.resolve("src"),
            root.resolve("out"), backend);
        checkProductionOutcome(fixture, backend + " (staged)", orchestrator);
        Path artifact = artifactOf(fixture, backend, root.resolve("out"));
        byte[] artifactBytes = Files.exists(artifact)
            ? Files.readAllBytes(artifact) : new byte[0];
        CompilationOrchestrator repeat = compile(source, root.resolve("src"),
            root.resolve("out-repeat"), backend);
        checkProductionOutcome(fixture, backend + " (repeated)", repeat);
        Path repeatArtifact = artifactOf(fixture, backend, root.resolve("out-repeat"));
        byte[] repeatBytes = Files.exists(repeatArtifact)
            ? Files.readAllBytes(repeatArtifact) : new byte[0];
        return new Compiled(root, orchestrator, artifact, artifactBytes, repeatBytes);
    }

    /** One quiet orchestrator compile over an explicit module root. */
    private static CompilationOrchestrator compile(Path entry, Path moduleRoot,
            Path outputRoot, Backend backend) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entry.toAbsolutePath(), outputRoot, false, false, false, false, backend, null,
            List.of(moduleRoot.toAbsolutePath()), null, null, productionInvocation());
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(
                ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(
                ByteArrayOutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
            orchestrator.compile();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return orchestrator;
    }

    /** The zero-E6005 / one-project-artifact / no-retained-emission gate. */
    private static void checkProductionOutcome(Fixture fixture, String target,
            CompilationOrchestrator orchestrator) {
        StringBuilder diagnostics = new StringBuilder();
        for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
            diagnostics.append(diagnostic.code()).append(' ')
                .append(diagnostic.message()).append('\n');
        }
        check(diagnostics.isEmpty(), fixture.what() + " (" + target
            + "): zero E6005 CONSTRUCT_UNLOWERED/RETAINED_ABI_DEFERRED/"
            + "SHARED_EMITTER_COVERAGE, zero diagnostics: " + diagnostics);
        checkEq(1, orchestrator.semanticEmissionCount(), fixture.what() + " (" + target
            + "): the production arm stages exactly one project artifact");
        checkEq(0, orchestrator.retainedEmissionCount(), fixture.what() + " (" + target
            + "): the production arm stages no retained emission");
        check(orchestrator.routePlan() == null, fixture.what() + " (" + target
            + "): the production arm consults no route plan");
    }

    /** The staged project artifact path of one fixture and target. */
    private static Path artifactOf(Fixture fixture, Backend backend, Path outputRoot) {
        return backend == Backend.LUAJIT
            ? outputRoot.resolve(fixture.relativePath() + ".lua")
            : outputRoot.resolve(JvmBackend.classNameFor(fixture.relativePath()) + ".java");
    }

    /**
     * The artifact-set invariant: exactly one project artifact and no
     * source-map sidecar.
     */
    private static void checkArtifactSet(Fixture fixture, Backend backend, Compiled compiled)
            throws Exception {
        check(Files.exists(compiled.artifact()), fixture.what() + " (" + backend
            + "): the staged project artifact exists at " + compiled.artifact());
        List<String> files = artifactFiles(compiled.outputRoot());
        if (backend == Backend.LUAJIT) {
            List<String> chunks = files.stream()
                .filter(name -> name.endsWith(".lua"))
                .filter(name -> !name.startsWith("deal/") && !name.startsWith("std/"))
                .toList();
            checkEq(List.of(fixture.relativePath() + ".lua"), chunks, fixture.what()
                + " (" + backend + "): exactly one project artifact chunk is staged");
        } else {
            List<String> classes = files.stream()
                .filter(name -> name.endsWith(".java")).toList();
            checkEq(List.of(JvmBackend.classNameFor(fixture.relativePath()) + ".java"),
                classes, fixture.what() + " (" + backend
                    + "): exactly one project artifact class is staged");
        }
        check(files.stream().noneMatch(name -> name.endsWith(".deal.map.json")),
            fixture.what() + " (" + backend + "): the artifact set carries no "
                + "source-map sidecar");
    }

    /** The one project lowering of one staged compile. */
    private static Lowered lower(Compiled compiled, Fixture fixture) {
        CompilationOrchestrator orchestrator = compiled.orchestrator();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null || manifests.manifests() == null
                || orchestrator.hostDeclarationSurface() == null) {
            fail(fixture.what() + ": the compiled facts are complete "
                + "(built/manifests/surface)");
            return null;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT), Set.of());
        check(!result.hasErrors() && result.project() != null, fixture.what()
            + ": the one project lowering reports zero diagnostics: "
            + result.diagnostics());
        if (result.project() == null) {
            return null;
        }
        ModuleId fixtureModule = null;
        for (ModuleId module : result.project().modules().keySet()) {
            if (module.path().equals(fixture.moduleKey())) {
                fixtureModule = module;
            }
        }
        check(fixtureModule != null, fixture.what() + ": the fixture module is part of "
            + "the closure: " + result.project().modules().keySet());
        if (fixtureModule == null) {
            return null;
        }
        LoweredModuleUnit unit = result.project().modules().get(fixtureModule);
        var gate = SemanticIrValidator.validate(result.project(),
            new SemanticIrValidator.ComparisonFacts(unit.interfaceHash(),
                SemanticProfile.DEAL_V1_2_INT32,
                ReleaseConfiguration.releaseCapabilityRegistry().capabilityRegistryHash()));
        check(gate.isEmpty(), fixture.what() + ": the closed schema and bindings gates "
            + "accept the produced closure: "
            + gate.map(CompilerDiagnostic::message).orElse("admission"));
        return new Lowered(result.project(), result.tables(), result.registries(),
            fixtureModule, unit);
    }

    // =========================================================================
    // 3. The staged artifact on the real toolchains
    // =========================================================================

    /** The async EXTERNAL_ENTRY op of the fixture module's export, or null. */
    private static SemanticOp asyncEntryOf(Lowered lowered, String export) {
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() == SemanticOpKind.EXTERNAL_ENTRY
                    && op.payload() instanceof KindPayload.ExternalEntryPayload payload
                    && payload.async() && export.equals(payload.exportName())) {
                return op;
            }
        }
        return null;
    }

    /** The LuaJIT probe driver: the deferred entry, then the exported probe. */
    private static String luaDriver(Fixture fixture, Path artifact) {
        StringBuilder assertValue = new StringBuilder();
        switch (fixture.kind()) {
            case MAIN -> assertValue.append("probe.f()\n");
            case SYNC -> assertValue.append("""
                local result = probe.f()
                if tostring(result) ~= "%s" then
                  print("ERR:VALUE|" .. tostring(result))
                  os.exit(1)
                end
                """.formatted(fixture.pinnedText()));
            case ASYNC -> assertValue.append("""
                probe.f()
                local entry = __asyncEntries["%s#%s"]
                if entry == nil then
                  print("ERR:ENTRY|the recorded async dispatch entry is missing")
                  os.exit(1)
                end
                local entryOk, completion = pcall(entry, "-", true)
                if not entryOk then
                  print("ERR:ENTRY|" .. tostring(completion))
                  os.exit(1)
                end
                if tostring(completion) ~= "%s" then
                  print("ERR:VALUE|" .. tostring(completion))
                  os.exit(1)
                end
                """.formatted(fixture.moduleKey(), fixture.probe(),
                    fixture.pinnedText()));
        }
        return """
            local surface = dofile("%s")
            local ok, err = __dealMain()
            if not ok then
              print("ERR:INIT|" .. tostring(err))
              os.exit(1)
            end
            local probe = surface["%s"]
            if type(probe) ~= "table" or probe.__kind ~= "function"
                or type(probe.f) ~= "function" then
              print("ERR:SHAPE|the entry surface publishes the fixture probe")
              os.exit(1)
            end
            %s""".formatted(artifact.toAbsolutePath(), fixture.probe(),
                assertValue.toString());
    }

    /** The JVM probe driver source: the export surface, then the exported probe. */
    private static String jvmDriver(Fixture fixture, String className, Lowered lowered) {
        StringBuilder drive = new StringBuilder();
        switch (fixture.kind()) {
            case MAIN -> drive.append("      fn.fn.invoke(new Object[0]);\n");
            case SYNC -> drive.append("""
                      Object result = fn.fn.invoke(new Object[0]);
                      if (!"%s".equals(String.valueOf(result))) {
                        System.out.println("ERR:VALUE|" + String.valueOf(result));
                        System.exit(1);
                      }
                """.formatted(fixture.pinnedText()));
            case ASYNC -> {
                SemanticOp entry = asyncEntryOf(lowered, fixture.probe());
                if (entry == null) {
                    fail(fixture.what() + " (JVM): the fixture module records an async "
                        + "EXTERNAL_ENTRY for '" + fixture.probe() + "'");
                    return null;
                }
                drive.append("""
                      fn.fn.invoke(new Object[0]);
                      Object completion = %s.ae%d("-", true, new Object[]{});
                      if (!"%s".equals(String.valueOf(completion))) {
                        System.out.println("ERR:VALUE|" + String.valueOf(completion));
                        System.exit(1);
                      }
                    """.formatted(className, entry.opId().id(), fixture.pinnedText()));
            }
        }
        return """
            final class ResidualProbe {
              public static void main(String[] args) {
                %s.dealMain();
                deal.codegen.jvm.JvmRuntime.Table surface =
                    %s.EXPORT_SURFACES.get("%s");
                if (surface == null) {
                  System.out.println("ERR:SURFACE|the entry surface is missing");
                  System.exit(1);
                }
                Object probe = surface.read("%s");
                if (!(probe instanceof deal.codegen.jvm.JvmRuntime.FunctionValue fn)) {
                  throw new IllegalStateException(
                      "the entry surface publishes the fixture probe");
                }
            %s  }
            }
            """.formatted(className, className, fixture.moduleKey(), fixture.probe(),
                drive.toString());
    }

    /** Executes the staged artifact on the real toolchain and checks the pin. */
    private static void runStagedArtifact(Fixture fixture, Backend backend, Compiled compiled,
            Lowered lowered) throws Exception {
        check(Arrays.equals(compiled.artifactBytes(), compiled.repeatBytes()),
            fixture.what() + " (" + backend + "): the repeated compile stages "
                + "byte-identical artifact bytes");
        SidecarExpectations.RuntimeExpectation.Executed pinned =
            pinnedExecuted(fixture, backend == Backend.LUAJIT ? "luajit" : "jvm");
        ProcessOutcome run;
        if (backend == Backend.LUAJIT) {
            Path driver = compiled.outputRoot().resolve("residual_probe_driver.lua");
            Files.writeString(driver, luaDriver(fixture, compiled.artifact()),
                StandardCharsets.UTF_8);
            run = runProcess(compiled.outputRoot(), Map.of("DEAL_DEFER_MAIN", "1"),
                "luajit", driver.getFileName().toString());
        } else {
            String className = JvmBackend.classNameFor(fixture.relativePath());
            String driver = jvmDriver(fixture, className, lowered);
            if (driver == null) {
                return;
            }
            Path driverFile = compiled.outputRoot().resolve("ResidualProbe.java");
            Files.writeString(driverFile, driver, StandardCharsets.UTF_8);
            Path classes = compiled.root().resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javac = runProcess(compiled.outputRoot(), Map.of(), "javac",
                "--release", "25", "-proc:none", "-cp", classpath, "-d",
                classes.toString(), compiled.artifact().toAbsolutePath().toString(),
                driverFile.toAbsolutePath().toString());
            checkEq(0, javac.exitCode(), fixture.what() + " (JVM): the artifact compiles "
                + "under javac --release 25 -proc:none: " + javac.stdout()
                + javac.stderr());
            if (javac.exitCode() != 0) {
                return;
            }
            run = runProcess(compiled.root(), Map.of(), "java", "-cp",
                classpath + File.pathSeparator + classes, "ResidualProbe");
        }
        checkEq(0, run.exitCode(), fixture.what() + " (" + backend + "): the staged "
            + "artifact exits 0: stdout=" + run.stdout() + " stderr=" + run.stderr());
        checkEq("", run.stdout(), fixture.what() + " (" + backend
            + "): the probe is silent (the sidecar-pinned empty stdout)");
        checkEq("", run.stderr(), fixture.what() + " (" + backend
            + "): the probe raises nothing (the sidecar-pinned empty stderr)");
        if (pinned != null) {
            checkEq(new String(pinned.stdout(), StandardCharsets.UTF_8), run.stdout(),
                fixture.what() + " (" + backend + "): stdout equals the sidecar pin");
            checkEq(new String(pinned.stderr(), StandardCharsets.UTF_8), run.stderr(),
                fixture.what() + " (" + backend + "): stderr equals the sidecar pin");
        }
    }

    private static SidecarExpectations.RuntimeExpectation.Executed pinnedExecuted(
            Fixture fixture, String backend) throws Exception {
        SidecarExpectations.StructuredExpectationSidecar parsed =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(sidecarPath(fixture), StandardCharsets.UTF_8));
        SidecarExpectations.RuntimeExpectation expectation = parsed.expectationFor(backend);
        return expectation instanceof SidecarExpectations.RuntimeExpectation.Executed executed
            ? executed : null;
    }

    // =========================================================================
    // 4. The oracle drives the same probes
    // =========================================================================

    /**
     * The oracle (and the trace-mode differential matrix) drive the same
     * probe: {@code main} through the project run, an async export through
     * its recorded async entry, a sync export through a driver entry that
     * asserts the pinned value.
     */
    private static void runOracleProbe(Fixture fixture, Lowered lowered) throws Exception {
        Path workspace = Files.createTempDirectory("residual-oracle-");
        try {
            switch (fixture.kind()) {
                case MAIN -> {
                    SemanticDifferentialHarness.Verdict verdict =
                        SemanticDifferentialHarness.runProject(lowered.project(),
                            lowered.tables(), lowered.registries(),
                            SemanticDifferentialHarness.Expectation.success(
                                fixture.what(), List.of(), "null"), workspace);
                    assertVerdict(fixture, verdict, "null");
                }
                case ASYNC -> {
                    SemanticDifferentialHarness.Verdict verdict =
                        SemanticDifferentialHarness.runAsyncEntry(lowered.project(),
                            lowered.tables(), fixture.probe(), List.of(),
                            SemanticDifferentialHarness.Expectation.success(
                                fixture.what(), List.of(), fixture.pinnedAtom()),
                            workspace, null);
                    assertVerdict(fixture, verdict, fixture.pinnedAtom());
                }
                case SYNC -> runSyncDriverProject(fixture, workspace);
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

    /** The sync probe's driver entry: it calls the probe and asserts the pin. */
    private static void runSyncDriverProject(Fixture fixture, Path workspace)
            throws Exception {
        Path src = workspace.resolve("driver").resolve("src");
        Path fixtureFile = src.resolve(fixture.relativePath() + ".deal");
        Files.createDirectories(fixtureFile.getParent());
        Files.writeString(fixtureFile, fixtureSource(fixture), StandardCharsets.UTF_8);
        Path driver = src.resolve("app.deal");
        Files.writeString(driver, "import * as fx from \"./" + fixture.relativePath()
            + "\"\n\nexport function main(): null {\n"
            + "  if (fx." + fixture.probe() + "() !== " + fixture.pinnedDeal() + ") {\n"
            + "    throw { code: \"TEST_FAIL\", message: \""
            + fixture.relativePath() + " pinned probe value\" }\n"
            + "  }\n  return null\n}\n", StandardCharsets.UTF_8);
        CompilationOrchestrator orchestrator = compile(driver, src, workspace.resolve("out"),
            Backend.LUAJIT);
        check(orchestrator.diagnostics().isEmpty(), fixture.what()
            + ": the sync driver project compiles: " + orchestrator.diagnostics());
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (built == null || built.input() == null || built.index() == null
                || manifests == null || manifests.manifests() == null) {
            fail(fixture.what() + ": the sync driver project's checked facts are complete");
            return;
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(), manifests.manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(), Map.of(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT), Set.of());
        check(!result.hasErrors() && result.project() != null, fixture.what()
            + ": the sync driver project lowers with zero diagnostics: "
            + result.diagnostics());
        if (result.project() == null) {
            return;
        }
        Path matrix = Files.createTempDirectory(workspace, "matrix-");
        SemanticDifferentialHarness.Verdict verdict =
            SemanticDifferentialHarness.runProject(result.project(), result.tables(),
                result.registries(), SemanticDifferentialHarness.Expectation.success(
                    fixture.what(), List.of(), "null"), matrix);
        assertVerdict(fixture, verdict, "null");
    }

    private static void assertVerdict(Fixture fixture,
            SemanticDifferentialHarness.Verdict verdict, String terminalText) {
        checkEq(3, verdict.runs().size(), fixture.what() + ": the differential matrix "
            + "produced the oracle and both shared consumers: " + verdict.failures());
        if (RECURSIVE_FIXTURES.contains(fixture.relativePath())) {
            // The recursive body's CALL op re-enters under its own open
            // START once per invocation; the harness's pairing sanity rule
            // records exactly those notices. Every other failure class — a
            // trace/event/effect/terminal divergence — must be absent, so
            // the three consumers agree event-for-event.
            boolean noticesOnly = verdict.failures().stream().allMatch(failure ->
                failure.contains("starts again before its previous terminal")
                    || failure.contains("terminates without an open START"));
            check(noticesOnly, fixture.what() + ": the traces agree event-for-event "
                + "(only the harness's non-reentrancy pairing notices remain for "
                + "the recursive CALL op): " + verdict.failures());
        } else {
            check(verdict.pass(), fixture.what() + ": the oracle and the shared "
                + "artifacts agree event-for-event on the pinned probe: "
                + verdict.failures());
        }
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success success
                    && terminalText.equals(success.resultAtom()),
                fixture.what() + ": " + run.consumer() + " completes with the pinned "
                    + "outcome: " + run.terminal());
        }
    }

    // =========================================================================
    // The union drive
    // =========================================================================

    private static void testUnionDrive(Path work, List<Outcome> outcomes) throws Exception {
        System.out.println("-- the union drive: 13 fixtures x 2 targets through the "
            + "release-owned production invocation --");
        for (Fixture fixture : FIXTURES) {
            for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
                Path root = Files.createTempDirectory(work, "union-");
                Compiled compiled = compileStaged(fixture, backend, root);
                checkArtifactSet(fixture, backend, compiled);
                Lowered lowered = lower(compiled, fixture);
                if (lowered == null) {
                    continue;
                }
                runStagedArtifact(fixture, backend, compiled, lowered);
                if (backend == Backend.LUAJIT) {
                    // The oracle is target-independent; it drives the same
                    // probe once per fixture.
                    runOracleProbe(fixture, lowered);
                }
                if (TAIL_FIXTURES.contains(fixture.relativePath())) {
                    assertTailArtifacts(fixture, backend, compiled, lowered);
                }
                outcomes.add(new Outcome(fixture, backend, compiled, lowered));
            }
        }
    }

    /**
     * The per-target represented-tail artifact rule: the Lua artifact
     * emits the tail (the ops after the terminator carry their slots) with
     * no reachability skip marker, and the JVM artifact keeps the
     * reachability skip.
     */
    private static void assertTailArtifacts(Fixture fixture, Backend backend,
            Compiled compiled, Lowered lowered) throws Exception {
        List<SemanticOp> tail = tailOps(lowered);
        check(!tail.isEmpty(), fixture.what() + ": the tail after the terminator is "
            + "represented in the lowered unit");
        if (backend == Backend.LUAJIT) {
            String text = Files.readString(compiled.artifact(), StandardCharsets.UTF_8);
            check(!text.contains("unreachable"), fixture.what() + " (LuaJIT): the Lua "
                + "artifact emits the tail (no reachability skip marker)");
            for (SemanticOp op : tail) {
                if (op.result() instanceof ValueId value) {
                    check(text.contains("S.v" + value.id()), fixture.what()
                        + " (LuaJIT): the tail op " + op.opId() + " ("
                        + op.kind() + ") is emitted in the Lua artifact");
                }
            }
        } else {
            String text = Files.readString(compiled.artifact(), StandardCharsets.UTF_8);
            check(text.contains("// unreachable"), fixture.what() + " (JVM): the JVM "
                + "artifact keeps the JLS §14.21 reachability skip");
        }
    }

    /** The ops of the lowered unit that follow a terminator in their own block. */
    private static List<SemanticOp> tailOps(Lowered lowered) {
        List<SemanticOp> tail = new ArrayList<>();
        for (BlockId block : lowered.tables().get(lowered.fixtureModule())
                .blockOps().keySet()) {
            List<SemanticOp> ops = lowered.opsOfBlock(block);
            boolean terminated = false;
            for (SemanticOp op : ops) {
                if (terminated) {
                    tail.add(op);
                }
                if (isTerminator(op.kind())) {
                    terminated = true;
                }
            }
        }
        return tail;
    }

    private static boolean isTerminator(SemanticOpKind kind) {
        return kind == SemanticOpKind.RETURN || kind == SemanticOpKind.THROW
            || kind == SemanticOpKind.BREAK || kind == SemanticOpKind.CONTINUE;
    }

    // =========================================================================
    // 5. The dual-mechanism joint acceptance
    // =========================================================================

    /**
     * The joint acceptance with landed BYTES: each dual-mechanism fixture's
     * lowered unit carries the residual mechanism (nested declared bodies
     * with their invocation identities, or a nested async declaration
     * awaited in place) <em>and</em> the landed bytes operations in the
     * same unit, so the drive fails if either realization regresses.
     */
    private static void testDualMechanismJointAcceptance(List<Outcome> outcomes)
            throws Exception {
        System.out.println("-- the dual-mechanism fixtures, joint with landed BYTES --");
        for (String path : DUAL_MECHANISM) {
            Fixture fixture = fixture(path);
            int driven = 0;
            for (Outcome outcome : outcomes) {
                if (!outcome.fixture().relativePath().equals(path)) {
                    continue;
                }
                driven++;
                Lowered lowered = outcome.lowered();
                long nestedBodies = nestedDeclaredBodies(lowered);
                check(nestedBodies >= 1, path + " (" + outcome.backend()
                    + "): the lowered unit carries the fixture's nested declared "
                    + "bodies, got " + nestedBodies);
                check(carriesBytesConstruct(lowered.unit()), path + " ("
                    + outcome.backend() + "): the landed bytes operations are in "
                    + "the same lowered unit");
            }
            checkEq(2, driven, path + ": the fixture is driven on both targets");
        }
        // The bytes-async-closure fixture additionally carries the nested
        // async declaration awaited in place (the AWAIT completion cell).
        for (Outcome outcome : outcomes) {
            if (!outcome.fixture().relativePath().equals("bytes/bytes-async-closure")) {
                continue;
            }
            check(outcome.lowered().unit().ops().stream()
                    .anyMatch(op -> op.kind() == SemanticOpKind.AWAIT),
                "bytes-async-closure (" + outcome.backend() + "): the nested async "
                    + "declaration's awaiting site lowers (the AWAIT completion cell)");
        }
    }

    /**
     * Whether the unit carries the landed bytes element contract: the
     * allocation intrinsic, a bytes index normalization, a bytes element
     * boundary cell, a bytes slot assignment, or a bytes-bearing descriptor.
     */
    private static boolean carriesBytesConstruct(LoweredModuleUnit unit) {
        for (SemanticOp op : unit.ops()) {
            if (op.kind() == SemanticOpKind.INTRINSIC_CALL
                    && op.payload() instanceof KindPayload.IntrinsicCallPayload intrinsic
                    && intrinsic.kind() == IntrinsicKind.BYTES_NEW) {
                return true;
            }
            if (op.payload() instanceof KindPayload.IndexNormalizePayload normalize
                    && (normalize.mode() == deal.semantic.ir.IndexMode.BYTES_READ
                        || normalize.mode() == deal.semantic.ir.IndexMode.BYTES_WRITE)) {
                return true;
            }
            if (op.payload() instanceof KindPayload.BoundaryPayload boundary
                    && (boundary.kind() == deal.semantic.ir.BoundaryKind.BYTE_ELEMENT_READ
                        || boundary.kind()
                            == deal.semantic.ir.BoundaryKind.BYTE_ELEMENT_ASSIGNMENT)) {
                return true;
            }
            if (op.kind() == SemanticOpKind.ASSIGN
                    && op.payload() instanceof KindPayload.AssignPayload assign
                    && assign.targetKind()
                        == deal.semantic.ir.AssignTargetKind.BYTES_SLOT) {
                return true;
            }
        }
        return false;
    }

    /** The lowered-body bindings declared inside a body (not the unit top level). */
    private static long nestedDeclaredBodies(Lowered lowered) {
        long count = 0;
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() != SemanticOpKind.CLOSURE_NEW) {
                continue;
            }
            var binding = lowered.unit().functionBindings().get(
                new deal.semantic.ir.FunctionAllocationIdentity(
                    ((ValueId) op.result()).id()));
            if (binding instanceof deal.semantic.ir.FunctionExecutionBinding.LoweredBody) {
                BlockId parent = lowered.tables().get(lowered.fixtureModule())
                    .opBlocks().get(op.opId());
                // A module-init-level closure has the module-init block as its
                // parent; a body-nested declaration is produced inside a
                // function's own block tree.
                if (parent != null && !lowered.tables().get(lowered.fixtureModule())
                        .blockOps().get(lowered.unit().moduleInit().initBlock())
                        .contains(op.opId())) {
                    count++;
                }
            } else {
                fail("a CLOSURE_NEW without a LoweredBody binding");
            }
        }
        return count;
    }

    // =========================================================================
    // 6. The represented-tail monotonicity
    // =========================================================================

    private static final String TAIL_PROBE = """
        export function probe(c: boolean): int {
          if (c) { return 1; } else { return 2; }
          let tailMarker: int = 715;
          if (tailMarker === 715) {
            throw { code: "TAIL_EXECUTED", message: "TAIL715" }
          }
          return 3;
        }

        export function main(): null {
          return null;
        }
        """;

    private static void testRepresentedTailMonotonicity(Path work, List<Outcome> outcomes)
            throws Exception {
        System.out.println("-- the represented-tail monotonicity on the union --");
        for (String path : TAIL_FIXTURES) {
            Fixture fixture = fixture(path);
            for (Outcome outcome : outcomes) {
                if (!outcome.fixture().relativePath().equals(path)
                        || outcome.backend() != Backend.LUAJIT) {
                    continue;
                }
                Lowered lowered = outcome.lowered();
                List<SemanticOp> tail = tailOps(lowered);
                check(!tail.isEmpty(), path + ": the statement after the terminator is "
                    + "a member of its block after the terminator");
                check(noSyntheticReturnsInTailBlocks(lowered), path + ": no implicit "
                    + "return is fabricated in a block marked non-OPEN by a terminator "
                    + "whose walk continued");
            }
        }

        // The unit-level both-branch-return body with a following
        // unreachable statement: the same marking, on both targets, with
        // the per-target emission rule (Lua emits the tail, the JVM keeps
        // the reachability skip and compiles under javac).
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
            Path root = Files.createTempDirectory(work, "tail-probe-");
            Path source = root.resolve("src").resolve("app.deal");
            Files.createDirectories(source.getParent());
            Files.writeString(source, TAIL_PROBE, StandardCharsets.UTF_8);
            CompilationOrchestrator orchestrator = compile(source, root.resolve("src"),
                root.resolve("out"), backend);
            StringBuilder diagnostics = new StringBuilder();
            for (CompilerDiagnostic diagnostic : orchestrator.diagnostics()) {
                diagnostics.append(diagnostic.code()).append(' ')
                    .append(diagnostic.message()).append('\n');
            }
            check(diagnostics.isEmpty(), "the unit-level tail probe (" + backend
                + "): the release-owned production invocation compiles with zero "
                + "diagnostics: " + diagnostics);
            checkEq(1, orchestrator.semanticEmissionCount(), "the unit-level tail probe ("
                + backend + "): one project artifact is staged");
            checkEq(0, orchestrator.retainedEmissionCount(), "the unit-level tail probe ("
                + backend + "): no retained emission");
            if (backend == Backend.LUAJIT) {
                Path artifact = root.resolve("out").resolve("app.lua");
                String text = Files.readString(artifact, StandardCharsets.UTF_8);
                check(text.contains("TAIL715"), "the unit-level tail probe (LuaJIT): "
                    + "the Lua artifact emits the tail (the TAIL715 statement)");
                Path driver = root.resolve("out").resolve("tail_probe_driver.lua");
                Files.writeString(driver, """
                    local surface = dofile("%s")
                    local ok, err = __dealMain()
                    if not ok then print("ERR:INIT"); os.exit(1) end
                    local probe = surface["probe"]
                    print("RESULT:" .. tostring(probe.f(true)))
                    print("RESULT:" .. tostring(probe.f(false)))
                    """.formatted(artifact.toAbsolutePath()), StandardCharsets.UTF_8);
                ProcessOutcome run = runProcess(root.resolve("out"),
                    Map.of("DEAL_DEFER_MAIN", "1"), "luajit", driver.getFileName().toString());
                checkEq(0, run.exitCode(), "the unit-level tail probe (LuaJIT): exit 0");
                checkEq("RESULT:1\nRESULT:2\n", run.stdout(), "the unit-level tail probe "
                    + "(LuaJIT): the body returns its branch value and the represented "
                    + "tail never executes");
                checkEq("", run.stderr(), "the unit-level tail probe (LuaJIT): silent");
            } else {
                String className = JvmBackend.classNameFor("app");
                Path artifact = root.resolve("out").resolve(className + ".java");
                String text = Files.readString(artifact, StandardCharsets.UTF_8);
                check(text.contains("// unreachable"), "the unit-level tail probe (JVM): "
                    + "the JVM artifact keeps the reachability skip");
                Path driverFile = root.resolve("out").resolve("TailProbe.java");
                Files.writeString(driverFile, """
                    final class TailProbe {
                      public static void main(String[] args) {
                        %s.dealMain();
                        deal.codegen.jvm.JvmRuntime.Table surface =
                            %s.EXPORT_SURFACES.get("app");
                        deal.codegen.jvm.JvmRuntime.FunctionValue fn =
                            (deal.codegen.jvm.JvmRuntime.FunctionValue)
                                surface.read("probe");
                        System.out.println("RESULT:" + fn.fn.invoke(new Object[]{true}));
                        System.out.println("RESULT:" + fn.fn.invoke(new Object[]{false}));
                      }
                    }
                    """.formatted(className, className), StandardCharsets.UTF_8);
                Path classes = root.resolve("classes");
                Files.createDirectories(classes);
                String classpath = absoluteClasspath();
                ProcessOutcome javac = runProcess(root.resolve("out"), Map.of(), "javac",
                    "--release", "25", "-proc:none", "-cp", classpath, "-d",
                    classes.toString(), artifact.toAbsolutePath().toString(),
                    driverFile.toAbsolutePath().toString());
                checkEq(0, javac.exitCode(), "the unit-level tail probe (JVM): the "
                    + "artifact compiles under javac --release 25 -proc:none: "
                    + javac.stdout() + javac.stderr());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(root, Map.of(), "java", "-cp",
                        classpath + File.pathSeparator + classes, "TailProbe");
                    checkEq(0, run.exitCode(), "the unit-level tail probe (JVM): exit 0");
                    checkEq("RESULT:1\nRESULT:2\n", run.stdout(), "the unit-level tail "
                        + "probe (JVM): the body returns its branch value and the "
                        + "represented tail never executes");
                    checkEq("", run.stderr(), "the unit-level tail probe (JVM): silent");
                }
            }
            // The marked-state monotonicity on the unit-level probe: the
            // both-branch-return body is marked non-OPEN while its walk
            // continues, so the trailing unreachable statement is a member
            // of the body block after the composite and no implicit return
            // is fabricated.
            Path artifact = backend == Backend.LUAJIT
                ? root.resolve("out").resolve("app.lua")
                : root.resolve("out").resolve(JvmBackend.classNameFor("app") + ".java");
            Lowered lowered = lower(new Compiled(root, orchestrator, artifact,
                new byte[0], new byte[0]), new Fixture("app", "probe", ProbeKind.SYNC,
                    "0", "0", "int:0"));
            if (lowered != null) {
                List<SemanticOp> tail = opsAfterCompositeBranch(lowered);
                check(!tail.isEmpty(), "the unit-level tail probe (" + backend
                    + "): the both-branch-return body's following unreachable "
                    + "statement is a member of its block after the composite");
                check(noSyntheticReturns(lowered), "the unit-level tail probe ("
                    + backend + "): the body block stays marked terminated and no "
                    + "implicit return is fabricated");
                check(lowered.unit().ops().stream().anyMatch(
                        op -> op.kind() == SemanticOpKind.THROW),
                    "the unit-level tail probe (" + backend + "): the unreachable "
                        + "TAIL715 throw is represented in the lowered unit");
            }
            deleteRecursively(root);
        }
    }

    /**
     * The ops that follow a composite {@code BRANCH} in a function body
     * block (the unit-level both-branch-return probe's represented tail).
     */
    private static List<SemanticOp> opsAfterCompositeBranch(Lowered lowered) {
        List<SemanticOp> tail = new ArrayList<>();
        for (var function : lowered.unit().functions().values()) {
            boolean afterBranch = false;
            for (SemanticOp op : lowered.opsOfBlock(function.body())) {
                if (afterBranch) {
                    tail.add(op);
                }
                if (op.kind() == SemanticOpKind.BRANCH) {
                    afterBranch = true;
                }
            }
        }
        return tail;
    }

    /** Whether the unit carries no implicit synthetic return anywhere. */
    private static boolean noSyntheticReturns(Lowered lowered) {
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() == SemanticOpKind.RETURN && op.origin() != null
                    && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a block that carries a terminator with following ops (a
     * marked, still-walked block) carries no implicit synthetic return.
     */
    private static boolean noSyntheticReturnsInTailBlocks(Lowered lowered) {
        Set<BlockId> tailBlocks = new LinkedHashSet<>();
        for (BlockId block : lowered.tables().get(lowered.fixtureModule())
                .blockOps().keySet()) {
            List<SemanticOp> ops = lowered.opsOfBlock(block);
            boolean terminated = false;
            for (SemanticOp op : ops) {
                if (terminated) {
                    tailBlocks.add(block);
                }
                if (isTerminator(op.kind())) {
                    terminated = true;
                }
            }
        }
        for (BlockId block : tailBlocks) {
            for (SemanticOp op : lowered.opsOfBlock(block)) {
                if (op.kind() == SemanticOpKind.RETURN && op.origin() != null
                        && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                    return false;
                }
            }
        }
        return true;
    }

    // =========================================================================
    // 7. The truncating int32 remainder
    // =========================================================================

    private static final String REMAINDER_SOURCE = """
        export function negModPos(): int { return -5 % 2 }
        export function posModNeg(): int { return 5 % -2 }
        export function negModNeg(): int { return -5 % -2 }
        export function posModPos(): int { return 5 % 2 }
        export function minModNegOne(): int { return -2147483648 % -1 }

        export function main(): null {
          if (-5 % 2 !== -1 || 5 % -2 !== 1 || -5 % -2 !== -1 || 5 % 2 !== 1
              || -2147483648 % -1 !== 0) {
            throw { code: "TEST_FAIL", message: "truncating int32 remainder mismatch" }
          }
          return null;
        }
        """;

    /** The five sign and boundary remainder cases (the DEAL expression, the pin). */
    private static final List<String[]> REMAINDER_CASES = List.of(
        new String[] {"negModPos", "-1"},
        new String[] {"posModNeg", "1"},
        new String[] {"negModNeg", "-1"},
        new String[] {"posModPos", "1"},
        new String[] {"minModNegOne", "0"});

    private static void testInt32RemainderTruncation(Path work) throws Exception {
        System.out.println("-- the truncating int32 remainder on both targets --");
        Fixture probe = new Fixture("app", "main", ProbeKind.MAIN, null, "null", "null");
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
            Path root = Files.createTempDirectory(work, "remainder-");
            Path source = root.resolve("src").resolve("app.deal");
            Files.createDirectories(source.getParent());
            Files.writeString(source, REMAINDER_SOURCE, StandardCharsets.UTF_8);
            CompilationOrchestrator orchestrator = compile(source, root.resolve("src"),
                root.resolve("out"), backend);
            checkProductionOutcome(probe, backend + " (the int32 remainder probe)",
                orchestrator);
            StringBuilder asserts = new StringBuilder();
            for (String[] remainderCase : REMAINDER_CASES) {
                asserts.append("  assert(tostring(surface[\"").append(remainderCase[0])
                    .append("\"].f()) == \"").append(remainderCase[1])
                    .append("\", \"").append(remainderCase[0]).append("\")\n");
            }
            if (backend == Backend.LUAJIT) {
                Path artifact = root.resolve("out").resolve("app.lua");
                Path driver = root.resolve("out").resolve("remainder_driver.lua");
                Files.writeString(driver, """
                    local surface = dofile("%s")
                    local ok, err = __dealMain()
                    if not ok then print("ERR:INIT"); os.exit(1) end
                    %s  print("REMAINDER-OK")
                    """.formatted(artifact.toAbsolutePath(), asserts.toString()),
                    StandardCharsets.UTF_8);
                ProcessOutcome run = runProcess(root.resolve("out"),
                    Map.of("DEAL_DEFER_MAIN", "1"), "luajit",
                    driver.getFileName().toString());
                checkEq(0, run.exitCode(), "the int32 remainder probe (LuaJIT): exit 0: "
                    + run.stdout() + run.stderr());
                checkEq("REMAINDER-OK\n", run.stdout(), "the int32 remainder probe "
                    + "(LuaJIT): the truncating remainder matches the pinned sign and "
                    + "boundary cases");
            } else {
                String className = JvmBackend.classNameFor("app");
                Path artifact = root.resolve("out").resolve(className + ".java");
                StringBuilder jvmAsserts = new StringBuilder();
                for (String[] remainderCase : REMAINDER_CASES) {
                    jvmAsserts.append("    require(\"").append(remainderCase[0])
                        .append("\", \"").append(remainderCase[1])
                        .append("\", fn(surface, \"").append(remainderCase[0])
                        .append("\"));\n");
                }
                Path driverFile = root.resolve("out").resolve("RemainderProbe.java");
                Files.writeString(driverFile, """
                    final class RemainderProbe {
                      static Object fn(deal.codegen.jvm.JvmRuntime.Table surface, String name) {
                        Object value = surface.read(name);
                        if (!(value instanceof deal.codegen.jvm.JvmRuntime.FunctionValue f)) {
                          throw new IllegalStateException("no probe " + name);
                        }
                        return f.fn.invoke(new Object[0]);
                      }

                      static void require(String name, String expected, Object actual) {
                        if (!expected.equals(String.valueOf(actual))) {
                          throw new IllegalStateException(
                              name + " = " + String.valueOf(actual));
                        }
                      }

                      public static void main(String[] args) {
                        %s.dealMain();
                        deal.codegen.jvm.JvmRuntime.Table surface =
                            %s.EXPORT_SURFACES.get("app");
                    %s    System.out.println("REMAINDER-OK");
                      }
                    }
                    """.formatted(className, className, jvmAsserts.toString()),
                    StandardCharsets.UTF_8);
                Path classes = root.resolve("classes");
                Files.createDirectories(classes);
                String classpath = absoluteClasspath();
                ProcessOutcome javac = runProcess(root.resolve("out"), Map.of(), "javac",
                    "--release", "25", "-proc:none", "-cp", classpath, "-d",
                    classes.toString(), artifact.toAbsolutePath().toString(),
                    driverFile.toAbsolutePath().toString());
                checkEq(0, javac.exitCode(), "the int32 remainder probe (JVM): compiles "
                    + "under javac --release 25 -proc:none: " + javac.stdout()
                    + javac.stderr());
                if (javac.exitCode() == 0) {
                    ProcessOutcome run = runProcess(root, Map.of(), "java", "-cp",
                        classpath + File.pathSeparator + classes, "RemainderProbe");
                    checkEq(0, run.exitCode(), "the int32 remainder probe (JVM): exit 0: "
                        + run.stdout() + run.stderr());
                    checkEq("REMAINDER-OK\n", run.stdout(), "the int32 remainder probe "
                        + "(JVM): the truncating remainder matches the pinned sign and "
                        + "boundary cases");
                }
            }
            if (backend == Backend.LUAJIT) {
                // The oracle and the two trace-mode shared consumers agree on
                // the same probe (the project's own main asserts the five
                // cases).
                Lowered lowered = lower(new Compiled(root, orchestrator,
                    root.resolve("out").resolve("app.lua"), new byte[0], new byte[0]),
                    probe);
                if (lowered != null) {
                    Path workspace = Files.createTempDirectory(work, "remainder-matrix-");
                    try {
                        SemanticDifferentialHarness.Verdict verdict =
                            SemanticDifferentialHarness.runProject(lowered.project(),
                                lowered.tables(), lowered.registries(),
                                SemanticDifferentialHarness.Expectation.success(
                                    "int32 remainder", List.of(), "null"), workspace);
                        assertVerdict(probe, verdict, "null");
                    } finally {
                        deleteRecursively(workspace);
                    }
                }
            }
            deleteRecursively(root);
        }
    }

    // =========================================================================
    // 9. The union invariants
    // =========================================================================

    /** The corpus-book bytes (fixture sources and sidecars) before the drive. */
    private static Map<String, byte[]> snapshotCorpus() throws Exception {
        Map<String, byte[]> snapshot = new LinkedHashMap<>();
        for (Fixture fixture : FIXTURES) {
            for (String suffix : List.of(".deal", ".expect.json")) {
                Path file = CORPUS.resolve(fixture.relativePath() + suffix);
                if (Files.exists(file)) {
                    snapshot.put(fixture.relativePath() + suffix,
                        Files.readAllBytes(file));
                }
            }
        }
        return snapshot;
    }

    private static void testUnionInvariants(Map<String, byte[]> before) throws Exception {
        System.out.println("-- the union invariants --");
        // The sidecars and sources are read, never written.
        Map<String, byte[]> after = snapshotCorpus();
        checkEq(before.keySet(), after.keySet(),
            "the corpus fixture and sidecar membership is unchanged");
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            check(Arrays.equals(entry.getValue(), after.get(entry.getKey())),
                entry.getKey() + ": the corpus file is byte-identical after the drive "
                    + "(read, never written)");
        }
        // The corpus count pin stays landed.
        String corpusTest = Files.readString(
            Path.of("test", "conformance", "DifferentialGateLanesCorpusTest.java"),
            StandardCharsets.UTF_8);
        check(corpusTest.contains("runtimeCasesDispatched() == 390")
                && corpusTest.contains("luajitCounts[0] == 390"),
            "the dispatched corpus count pin (390) stays landed");
        // The guard identifiers stay landed.
        checkEq("CONSTRUCT_UNLOWERED", SemanticLowerer.CONSTRUCT_UNLOWERED,
            "the CONSTRUCT_UNLOWERED guard identifier stays landed");
        checkEq("RETAINED_ABI_DEFERRED", SemanticLowerer.RETAINED_ABI_DEFERRED,
            "the RETAINED_ABI_DEFERRED guard identifier stays landed");
        checkEq("SHARED_EMITTER_COVERAGE", ProductionProjectEmission.SHARED_EMITTER_COVERAGE,
            "the SHARED_EMITTER_COVERAGE guard identifier stays landed");
        // No test file is removed.
        for (String file : List.of("ControlFlowLoweringTest.java",
                "ControlFlowValidatorTest.java", "EvaluationOrderIntegrationTest.java",
                "SemanticProductionGateTest.java", "CompositeTerminatorAnalysisTest.java",
                "BytesCoverageTest.java")) {
            check(Files.exists(Path.of("test", file)),
                "the test file " + file + " stays landed");
        }
        // The lane mechanisms, the sidecar schema, and the JS lane stay
        // landed and untouched by this change.
        for (String file : List.of("test/conformance/LuaLane.java",
                "test/conformance/JvmLane.java", "test/conformance/JsLane.java",
                "test/conformance/SidecarSchemaValidator.java", "deal/runtime.js")) {
            check(Files.exists(Path.of(file)), file + " stays landed");
        }
        // This test is a foreground record of the shared gate manifest.
        String manifest = Files.readString(Path.of("tools", "gate-manifest.sh"),
            StandardCharsets.UTF_8);
        check(manifest.contains("'fg|")
                && manifest.contains("deal.test.ResidualCarrierShapesAcceptanceTest"),
            "this acceptance drive is registered as a foreground gate record");
    }

    // =========================================================================
    // Process and file helpers
    // =========================================================================

    private static ProcessOutcome runProcess(Path directory, Map<String, String> env,
            String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(false);
        builder.environment().putAll(env);
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new ProcessOutcome(exit, stdout, stderr);
    }

    /** The absolute compile classpath of this test JVM (never cwd-relative). */
    private static String absoluteClasspath() {
        StringBuilder joined = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(File.pathSeparator);
            }
            joined.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return joined.toString();
    }

    private static List<String> artifactFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .sorted().toList();
        }
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception ignored) {
            // Best-effort temp cleanup only; never part of a test result.
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Residual Carrier Shapes Acceptance Tests (ISSUE-0715) ===\n");

        Map<String, byte[]> corpusBefore = snapshotCorpus();
        Path work = Files.createTempDirectory("residual-acceptance-");
        List<Outcome> outcomes = new ArrayList<>();
        try {
            testInventoryAndPins();
            testUnionDrive(work, outcomes);
            testDualMechanismJointAcceptance(outcomes);
            testRepresentedTailMonotonicity(work, outcomes);
            testInt32RemainderTruncation(work);
            testUnionInvariants(corpusBefore);
        } finally {
            deleteRecursively(work);
        }

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
