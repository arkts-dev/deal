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
import deal.semantic.ir.FunctionAllocationIdentity;
import deal.semantic.ir.FunctionExecutionBinding;
import deal.semantic.ir.IntrinsicKind;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredFunction;
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
import java.io.InputStream;
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
import java.util.concurrent.TimeUnit;
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
 *       (the lane-equivalent invocation sequence: {@code main} through the
 *       entry delegation, a sync export called directly, an async export
 *       through its recorded async dispatch entry) with the sidecar-pinned
 *       outcome; the oracle
 *       drives the same probes event-for-event through the differential
 *       matrix with no divergence.</li>
 *   <li><b>The dual-mechanism joint acceptance.</b> The two bytes
 *       fixtures carry the nested declared bodies and the nested async
 *       declarations the residual mechanisms own <em>and</em> the landed
 *       bytes element contract in the same lowered unit; a broken bytes
 *       realization or a broken residual mechanism fails the same
 *       drive.</li>
 *   <li><b>The represented-tail monotonicity.</b> The two tail fixtures
 *       keep the enclosing body of their represented tail non-{@code OPEN}
 *       while the walk continues (the body root carries no implicit
 *       synthetic return); a unit-level <em>null-returning</em>
 *       both-branch-return body whose represented tail ends in a
 *       non-terminating statement and whose body carries no final return
 *       that could re-mark it asserts the same state on both targets, and
 *       a patched-lowerer negative control proves the assertion rejects
 *       the exit-state-reset mutation. The return/throw/try-catch
 *       composite regressions with tails inside their child blocks prove
 *       the JVM reachability-prefix decision: the emitted artifact
 *       compiles and executes under {@code javac --release 25 -proc:none}
 *       plus {@code java}, the Lua artifact emits the tail, the oracle
 *       agrees, and the tail never executes.</li>
 *   <li><b>The union invariants.</b> The sidecars and fixture sources are
 *       read, never written; the epic's fail-closed producer guards keep
 *       their identifiers.</li>
 *   <li><b>The bounded real-toolchain execution.</b> Every child this
 *       drive launches (luajit, javac, java) runs through the bounded
 *       runner: separate transcripts, concurrent capped 1 MiB drains to
 *       EOF, one monotonic deadline over the child and its stream
 *       drainage, complete owned-tree termination (the child leads its
 *       own process group, so a descendant that outlives the direct child
 *       dies before the call returns), and the named hard
 *       {@code BOUNDED_PROCESS_TIMEOUT} failure — with a finite
 *       stderr-flood check, a timeout check, an interruption check, and
 *       descendant-cleanup checks for inherited pipes and redirected
 *       streams.</li>
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

    /**
     * The LuaJIT probe driver: the deferred entry (whose module-init walk
     * delegates the entry module's {@code main} exactly once), then the
     * fixture's probe — {@code main} through the entry delegation alone, a
     * sync export called directly, an async export through its recorded
     * async dispatch entry (the lane-equivalent invocation sequence).
     */
    private static String luaDriver(Fixture fixture, Path artifact) {
        StringBuilder assertValue = new StringBuilder();
        switch (fixture.kind()) {
            case MAIN -> {
                // The entry delegation already ran main exactly once.
            }
            case SYNC -> assertValue.append("""
                local result = probe.f()
                if tostring(result) ~= "%s" then
                  print("ERR:VALUE|" .. tostring(result))
                  os.exit(1)
                end
                """.formatted(fixture.pinnedText()));
            case ASYNC -> assertValue.append("""
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

    /**
     * The JVM probe driver source: the entry delegation, then the
     * fixture's probe — {@code main} through the entry delegation alone, a
     * sync export called directly, an async export through its recorded
     * async dispatch entry (the lane-equivalent invocation sequence).
     */
    private static String jvmDriver(Fixture fixture, String className, Lowered lowered) {
        StringBuilder drive = new StringBuilder();
        switch (fixture.kind()) {
            case MAIN -> {
                // The entry delegation already ran main exactly once.
            }
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

    /**
     * The unit-level represented-tail probes. Every probe carries a
     * composite whose child blocks carry a represented tail (a statement
     * after the child block's terminator) and a body-level represented
     * tail behind the composite; the drive compiles, emits, executes and
     * oracle-checks each on both targets.
     */
    private record TailUnitProbe(String name, String source, String luaAsserts,
                                 String jvmAsserts) {
    }

    private static final List<TailUnitProbe> TAIL_UNIT_PROBES = List.of(
        // The monotonicity probe: a null-returning body whose both
        // branches return null, whose represented body tail ends in a
        // non-terminating statement and which carries no final return that
        // could re-mark the block — only the retained composite marking
        // keeps the implicit null return un-fabricated (the
        // exit-state-reset negative control drives this probe).
        new TailUnitProbe("null-both-return", """
            export function probe(c: boolean): null {
              if (c) {
                return null;
                let x: int = 9715;
              } else {
                return null;
                let y: int = 9716;
              }
              let tailMarker: int = 715;
              if (tailMarker === 715) {
                throw { code: "TAIL_EXECUTED", message: "TAIL715" }
              }
            }

            export function main(): null {
              probe(true);
              probe(false);
              return null;
            }
            """, """
            probe.f(true)
            probe.f(false)
            """, """
                fn.fn.invoke(new Object[]{true});
                fn.fn.invoke(new Object[]{false});
            """),
        // The reported reproduction: an if/else whose both branches return
        // with tails, followed by a body-level tail the JVM emitter must
        // skip as unreachable Java.
        new TailUnitProbe("int-both-return", """
            export function probe(c: boolean): int {
              if (c) {
                return 1;
                let x: int = 9715;
              } else {
                return 2;
                let y: int = 9716;
              }
              return 3;
            }

            export function main(): null {
              if (probe(true) !== 1) {
                throw { code: "TEST_FAIL", message: "true branch" }
              }
              if (probe(false) !== 2) {
                throw { code: "TEST_FAIL", message: "false branch" }
              }
              return null;
            }
            """, """
            if probe.f(true) ~= 1 then print("ERR:VALUE"); os.exit(1) end
            if probe.f(false) ~= 2 then print("ERR:VALUE"); os.exit(1) end
            """, """
                if (!"1".equals(String.valueOf(fn.fn.invoke(new Object[]{true})))) {
                  System.out.println("ERR:VALUE|true branch");
                  System.exit(1);
                }
                if (!"2".equals(String.valueOf(fn.fn.invoke(new Object[]{false})))) {
                  System.out.println("ERR:VALUE|false branch");
                  System.exit(1);
                }
            """),
        // A throw composite with a represented tail in its protected child
        // block: the try block's reachable prefix ends in a THROW followed
        // by the represented tail, so the completion decision must stop at
        // the throw, not read the tail as the block's completion. The throw
        // is outside the preceding if-branch, so the oracle and both emitted
        // consumers agree on the composite's failure projection.
        new TailUnitProbe("throw-tail", """
            export function probe(c: boolean): string {
              try {
                if (!c) {
                  return "false";
                }
                throw { code: "E_C", message: "c" };
                let x: int = 9715;
              } catch (e) {
                return e.code;
              }
              return "TAIL_NEVER";
            }

            export function main(): null {
              if (probe(false) !== "false") {
                throw { code: "TEST_FAIL", message: "false branch" }
              }
              if (probe(true) !== "E_C") {
                throw { code: "TEST_FAIL", message: "throw branch" }
              }
              return null;
            }
            """, """
            if probe.f(false) ~= "false" then print("ERR:VALUE"); os.exit(1) end
            if probe.f(true) ~= "E_C" then print("ERR:VALUE"); os.exit(1) end
            """, """
                if (!"false".equals(String.valueOf(
                        fn.fn.invoke(new Object[]{false})))) {
                  System.out.println("ERR:VALUE|false branch");
                  System.exit(1);
                }
                if (!"E_C".equals(String.valueOf(
                        fn.fn.invoke(new Object[]{true})))) {
                  System.out.println("ERR:VALUE|throw branch");
                  System.exit(1);
                }
            """),
        // A try/catch whose both child blocks return with tails: the
        // completion decision must read the reachable emitted prefix of
        // each child block, not its last represented op.
        new TailUnitProbe("try-both-return", """
            export function probe(): string {
              try {
                return "try";
                let x: int = 9715;
              } catch (e) {
                return "catch";
                let y: int = 9716;
              }
              return "unreachable";
            }

            export function main(): null {
              if (probe() !== "try") {
                throw { code: "TEST_FAIL", message: "return composite" }
              }
              return null;
            }
            """, """
            if probe.f() ~= "try" then print("ERR:VALUE"); os.exit(1) end
            """, """
                if (!"try".equals(String.valueOf(fn.fn.invoke(new Object[0])))) {
                  System.out.println("ERR:VALUE|return composite");
                  System.exit(1);
                }
            """));

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
                assertCorpusTailBodyState(path, lowered);
            }
        }
        for (TailUnitProbe probe : TAIL_UNIT_PROBES) {
            for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM)) {
                driveTailUnitProbe(work, probe, backend);
            }
        }
    }

    /**
     * The relevant enclosing body state of one corpus tail fixture: the
     * lowered function body whose block subtree carries the represented
     * tail stays non-{@code OPEN} while the walk continues — its body root
     * carries no implicit synthetic return (implicit returns are generated
     * for body roots, not for arbitrary child blocks).
     */
    private static void assertCorpusTailBodyState(String path, Lowered lowered) {
        Set<BlockId> tails = tailBlocks(lowered);
        int owning = 0;
        for (LoweredFunction function : lowered.unit().functions().values()) {
            Set<BlockId> subtree = blockSubtree(lowered, function.body());
            if (java.util.Collections.disjoint(subtree, tails)) {
                continue;
            }
            owning++;
            for (SemanticOp op : lowered.opsOfBlock(function.body())) {
                if (op.kind() == SemanticOpKind.RETURN && op.origin() != null
                        && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                    fail(path + ": the body block " + function.body()
                        + " enclosing the represented tail stays non-OPEN "
                        + "(no implicit synthetic return, op " + op.opId() + ")");
                }
            }
        }
        checkEq(1, owning, path + ": exactly one lowered body encloses the "
            + "represented tail");
    }

    /** One unit-level tail probe and one target, through the whole drive. */
    private static void driveTailUnitProbe(Path work, TailUnitProbe probe, Backend backend)
            throws Exception {
        Path root = Files.createTempDirectory(work, "tail-unit-");
        try {
            Path source = root.resolve("src").resolve("app.deal");
            Files.createDirectories(source.getParent());
            Files.writeString(source, probe.source(), StandardCharsets.UTF_8);
            Fixture fixture = new Fixture("app", "probe", ProbeKind.SYNC, "0", "0",
                "int:0");
            CompilationOrchestrator orchestrator = compile(source, root.resolve("src"),
                root.resolve("out"), backend);
            checkProductionOutcome(fixture, probe.name() + " (" + backend + ")",
                orchestrator);
            Path artifact = artifactOf(fixture, backend, root.resolve("out"));
            check(Files.exists(artifact), probe.name() + " (" + backend
                + "): the staged project artifact exists at " + artifact);
            CompilationOrchestrator repeated = compile(source, root.resolve("src"),
                root.resolve("out-repeat"), backend);
            checkProductionOutcome(fixture,
                probe.name() + " (repeated, " + backend + ")", repeated);
            Path repeatedArtifact = artifactOf(fixture, backend,
                root.resolve("out-repeat"));
            check(Files.exists(artifact) && Files.exists(repeatedArtifact)
                    && Arrays.equals(Files.readAllBytes(artifact),
                        Files.readAllBytes(repeatedArtifact)),
                probe.name() + " (" + backend + "): the repeated compile stages "
                    + "byte-identical artifact bytes");
            Lowered lowered = lower(new Compiled(root, orchestrator, artifact,
                new byte[0], new byte[0]), fixture);
            if (lowered == null) {
                return;
            }
            assertTailUnitProbeState(probe, backend, lowered, artifact);
            runTailUnitProbe(probe, backend, root, artifact);
            if (backend == Backend.LUAJIT) {
                Path workspace = Files.createTempDirectory(work, "tail-unit-matrix-");
                try {
                    SemanticDifferentialHarness.Verdict verdict =
                        SemanticDifferentialHarness.runProject(lowered.project(),
                            lowered.tables(), lowered.registries(),
                            SemanticDifferentialHarness.Expectation.success(
                                probe.name(), List.of(), "null"), workspace);
                    assertVerdict(fixture, verdict, "null");
                } finally {
                    deleteRecursively(workspace);
                }
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /** The lowered-state and per-target emission assertions of one probe. */
    private static void assertTailUnitProbeState(TailUnitProbe probe, Backend backend,
            Lowered lowered, Path artifact) throws Exception {
        List<SemanticOp> tail = tailOps(lowered);
        check(!tail.isEmpty(), probe.name() + " (" + backend + "): the statements "
            + "after the child-block terminators are members of their blocks after "
            + "the terminators");
        BlockId probeBody = exportedBodyBlock(lowered, "probe");
        check(probeBody != null, probe.name() + " (" + backend + "): the exported "
            + "probe's lowered body block resolves");
        if (probeBody == null) {
            return;
        }
        List<SemanticOp> bodyOps = lowered.opsOfBlock(probeBody);
        int compositeIndex = -1;
        for (int i = 0; i < bodyOps.size(); i++) {
            SemanticOpKind kind = bodyOps.get(i).kind();
            if (kind == SemanticOpKind.BRANCH || kind == SemanticOpKind.TRY_CATCH) {
                compositeIndex = i;
                break;
            }
        }
        check(compositeIndex >= 0 && compositeIndex + 1 < bodyOps.size(),
            probe.name() + " (" + backend + "): the composite's following "
            + "represented tail is a member of the probe body block (op kinds "
            + opKinds(bodyOps) + ")");
        for (SemanticOp op : bodyOps) {
            if (op.kind() == SemanticOpKind.RETURN && op.origin() != null
                    && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                fail(probe.name() + " (" + backend + "): the probe body block "
                    + probeBody + " stays non-OPEN while the walk continues — no "
                    + "implicit synthetic return is fabricated (op " + op.opId() + ")");
            }
        }
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() == SemanticOpKind.RETURN && op.origin() != null
                    && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                fail(probe.name() + " (" + backend + "): the probe unit fabricates "
                    + "no implicit synthetic return (op " + op.opId() + ")");
            }
        }
        String text = Files.readString(artifact, StandardCharsets.UTF_8);
        if (backend == Backend.LUAJIT) {
            check(!text.contains("// unreachable"), probe.name() + " (LuaJIT): the "
                + "Lua artifact emits the tail (no reachability skip marker)");
            for (SemanticOp op : tail) {
                if (op.result() instanceof ValueId value) {
                    check(text.contains("S.v" + value.id()), probe.name()
                        + " (LuaJIT): the tail op " + op.opId() + " (" + op.kind()
                        + ") is emitted in the Lua artifact");
                }
            }
        } else {
            check(text.contains("// unreachable"), probe.name() + " (JVM): the JVM "
                + "artifact keeps the JLS §14.21 reachability skip");
        }
    }

    /** The real-toolchain execution of one unit-level tail probe. */
    private static void runTailUnitProbe(TailUnitProbe probe, Backend backend, Path root,
            Path artifact) throws Exception {
        ProcessOutcome run;
        if (backend == Backend.LUAJIT) {
            Path driver = root.resolve("out").resolve("tail_unit_driver.lua");
            Files.writeString(driver, """
                local surface = dofile("%s")
                local ok, err = __dealMain()
                if not ok then print("ERR:INIT"); os.exit(1) end
                local probe = surface["probe"]
                if type(probe) ~= "table" or probe.__kind ~= "function"
                    or type(probe.f) ~= "function" then
                  print("ERR:SHAPE"); os.exit(1)
                end
                %sprint("TAIL-UNIT-OK")
                """.formatted(artifact.toAbsolutePath(), probe.luaAsserts()),
                StandardCharsets.UTF_8);
            run = runProcess(root.resolve("out"), Map.of("DEAL_DEFER_MAIN", "1"),
                "luajit", driver.getFileName().toString());
        } else {
            String className = JvmBackend.classNameFor("app");
            Path driverFile = root.resolve("out").resolve("TailUnitProbeDriver.java");
            Files.writeString(driverFile, """
                final class TailUnitProbeDriver {
                  public static void main(String[] args) {
                    %s.dealMain();
                    deal.codegen.jvm.JvmRuntime.Table surface =
                        %s.EXPORT_SURFACES.get("app");
                    Object probeValue = surface.read("probe");
                    if (!(probeValue
                            instanceof deal.codegen.jvm.JvmRuntime.FunctionValue)) {
                      throw new IllegalStateException(
                          "the entry surface publishes the fixture probe");
                    }
                    deal.codegen.jvm.JvmRuntime.FunctionValue fn =
                        (deal.codegen.jvm.JvmRuntime.FunctionValue) probeValue;
                    %s    System.out.println("TAIL-UNIT-OK");
                  }
                }
                """.formatted(className, className, probe.jvmAsserts()),
                StandardCharsets.UTF_8);
            Path classes = root.resolve("classes");
            Files.createDirectories(classes);
            String classpath = absoluteClasspath();
            ProcessOutcome javac = runProcess(root.resolve("out"), Map.of(), "javac",
                "--release", "25", "-proc:none", "-cp", classpath, "-d",
                classes.toString(), artifact.toAbsolutePath().toString(),
                driverFile.toAbsolutePath().toString());
            checkEq(0, javac.exitCode(), probe.name() + " (JVM): the artifact compiles "
                + "under javac --release 25 -proc:none: " + javac.stdout()
                + javac.stderr());
            if (javac.exitCode() != 0) {
                return;
            }
            run = runProcess(root, Map.of(), "java", "-cp",
                classpath + File.pathSeparator + classes, "TailUnitProbeDriver");
        }
        checkEq(0, run.exitCode(), probe.name() + " (" + backend + "): the staged "
            + "artifact exits 0: stdout=" + run.stdout() + " stderr=" + run.stderr());
        checkEq("TAIL-UNIT-OK\n", run.stdout(), probe.name() + " (" + backend
            + "): the probe runs its branch paths and the represented tail never "
            + "executes");
        checkEq("", run.stderr(), probe.name() + " (" + backend + "): the probe is "
            + "silent");
    }

    /**
     * The exit-state-reset negative control: the production lowerer copied
     * with the monotonicity broken at both statement walks (the current
     * block's exit state erased before every statement of the walk — the
     * "exit-state-reset" mutation a regression of this criterion would
     * introduce), compiled against the production classes and driven over
     * the null-returning probe in a child JVM whose classpath carries the
     * patched class first. The patched build must fabricate the implicit
     * null return in the probe body, so the drive's "no synthetic return"
     * assertion rejects the reset control; the unpatched classpath keeps it
     * green.
     */
    private static void testExitStateResetNegativeControl(Path work) throws Exception {
        System.out.println("-- the exit-state-reset negative control --");
        String patched = resetPatchedLowerer(Files.readString(
            Path.of("deal", "semantic", "SemanticLowerer.java"), StandardCharsets.UTF_8));
        check(patched != null, "the exit-state-reset control's mutation anchors "
            + "(both statement walks) are present in the production lowerer");
        if (patched == null) {
            return;
        }
        Path root = Files.createTempDirectory(work, "reset-control-");
        try {
            Path patchedSource = root.resolve("patched").resolve("deal")
                .resolve("semantic").resolve("SemanticLowerer.java");
            Files.createDirectories(patchedSource.getParent());
            Files.writeString(patchedSource, patched, StandardCharsets.UTF_8);
            Path patchedClasses = root.resolve("classes");
            Files.createDirectories(patchedClasses);
            String classpath = absoluteClasspath();
            ProcessOutcome javac = runProcess(root, Map.of(), "javac", "--release", "25",
                "-proc:none", "-cp", classpath, "-d", patchedClasses.toString(),
                patchedSource.toAbsolutePath().toString());
            checkEq(0, javac.exitCode(), "the patched lowerer compiles against the "
                + "production classes: " + javac.stdout() + javac.stderr());
            if (javac.exitCode() != 0) {
                return;
            }
            TailUnitProbe probe = TAIL_UNIT_PROBES.get(0);
            Path source = root.resolve("probe").resolve("src").resolve("app.deal");
            Files.createDirectories(source.getParent());
            Files.writeString(source, probe.source(), StandardCharsets.UTF_8);
            ProcessOutcome plain = resetControlRun(root, source, classpath, null, "plain");
            checkEq(0, plain.exitCode(), "the unpatched control lowers the probe: "
                + plain.stdout() + plain.stderr());
            checkEq("RESET_CONTROL_SYNTHETIC_RETURN=false\n", plain.stdout(),
                "the unpatched control keeps the probe body's retained marking (no "
                    + "implicit synthetic return)");
            ProcessOutcome reset = resetControlRun(root, source, classpath,
                patchedClasses, "reset");
            checkEq(0, reset.exitCode(), "the patched control lowers the probe: "
                + reset.stdout() + reset.stderr());
            checkEq("RESET_CONTROL_SYNTHETIC_RETURN=true\n", reset.stdout(),
                "the exit-state-reset negative control is rejected: the reset "
                    + "mutation fabricates the implicit synthetic return the "
                    + "monotonicity assertion forbids");
        } finally {
            deleteRecursively(root);
        }
    }

    /** One child-JVM run of the reset control driver. */
    private static ProcessOutcome resetControlRun(Path root, Path source, String classpath,
            Path patchedClasses, String label) throws Exception {
        String controlClasspath = patchedClasses == null
            ? classpath
            : patchedClasses + File.pathSeparator + classpath;
        return runProcess(root, Map.of(), "java", "-cp", controlClasspath,
            "deal.test.ResidualCarrierShapesAcceptanceTest$ResetControlDriver",
            source.toAbsolutePath().toString(),
            root.resolve("out-" + label).toAbsolutePath().toString());
    }

    /**
     * The exit-state-reset mutation of the production lowerer (the
     * negative control): the current block's accumulated exit state is
     * erased before every statement of both statement walks. Returns
     * {@code null} when either mutation anchor is absent.
     */
    private static String resetPatchedLowerer(String text) {
        String result = text;
        for (String anchor : List.of(
                "private void lowerFullStatements(List<StatementNode> statements,",
                "private void lowerStatements(List<StatementNode> statements) {")) {
            int methodAt = result.indexOf(anchor);
            if (methodAt < 0) {
                return null;
            }
            String loop = "for (StatementNode statement : statements) {";
            int loopAt = result.indexOf(loop, methodAt);
            if (loopAt < 0) {
                return null;
            }
            int insertAt = loopAt + loop.length();
            result = result.substring(0, insertAt)
                + "\n                blockExitStates.remove(blockStack.peek());"
                + result.substring(insertAt);
        }
        return result;
    }

    /** The blocks that carry a terminator with a following represented op. */
    private static Set<BlockId> tailBlocks(Lowered lowered) {
        Set<BlockId> blocks = new LinkedHashSet<>();
        for (BlockId block : lowered.tables().get(lowered.fixtureModule())
                .blockOps().keySet()) {
            List<SemanticOp> ops = lowered.opsOfBlock(block);
            boolean terminated = false;
            for (SemanticOp op : ops) {
                if (terminated) {
                    blocks.add(block);
                    break;
                }
                if (isTerminator(op.kind())) {
                    terminated = true;
                }
            }
        }
        return blocks;
    }

    /** Every block of one function body's block subtree. */
    private static Set<BlockId> blockSubtree(Lowered lowered, BlockId root) {
        Set<BlockId> blocks = new LinkedHashSet<>();
        java.util.ArrayDeque<BlockId> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            BlockId block = queue.remove();
            if (!blocks.add(block)) {
                continue;
            }
            for (SemanticOp op : lowered.opsOfBlock(block)) {
                for (BlockId child : childBlocks(op)) {
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        }
        return blocks;
    }

    /** The child blocks a structure op's payload owns. */
    private static List<BlockId> childBlocks(SemanticOp op) {
        List<BlockId> children = new ArrayList<>();
        if (op.payload() instanceof KindPayload.BranchPayload branch) {
            children.add(branch.selectedBlock());
            if (branch.alternateBlock() != null) {
                children.add(branch.alternateBlock());
            }
        } else if (op.payload() instanceof KindPayload.TryCatchPayload tryCatch) {
            children.add(tryCatch.tryBlock());
            children.add(tryCatch.catchBlock());
        } else if (op.payload() instanceof KindPayload.LoopPayload loop) {
            children.add(loop.initBlock());
            children.add(loop.bodyBlock());
            if (loop.updateBlock() != null) {
                children.add(loop.updateBlock());
            }
        } else if (op.payload() instanceof KindPayload.ForEachPayload forEach) {
            children.add(forEach.body());
        }
        return children;
    }

    /** The lowered body block of one exported function, or null. */
    private static BlockId exportedBodyBlock(Lowered lowered, String export) {
        for (SemanticOp op : lowered.unit().ops()) {
            if (op.kind() != SemanticOpKind.EXPORT_PUBLISH
                    || !(op.payload()
                        instanceof KindPayload.ExportPublishPayload publish)
                    || !export.equals(publish.name())) {
                continue;
            }
            FunctionExecutionBinding binding = lowered.unit().functionBindings().get(
                new FunctionAllocationIdentity(publish.value().id()));
            if (binding instanceof FunctionExecutionBinding.LoweredBody body) {
                return body.blockId();
            }
        }
        return null;
    }

    /** One op list's kinds (failure diagnostics). */
    private static List<String> opKinds(List<SemanticOp> ops) {
        List<String> kinds = new ArrayList<>();
        for (SemanticOp op : ops) {
            kinds.add(op.kind().name());
        }
        return kinds;
    }

    /**
     * The exit-state-reset negative control's child-JVM driver: lowers the
     * probe project and reports whether the exported {@code probe} body
     * block carries an implicit synthetic return. Run with the patched
     * lowerer first on the classpath (the control) and without it (the
     * green counterpart).
     */
    public static final class ResetControlDriver {

        public static void main(String[] args) throws Exception {
            Path source = Path.of(args[0]);
            Path outputRoot = Path.of(args[1]);
            CompilationOrchestrator orchestrator = compile(source, source.getParent(),
                outputRoot, Backend.LUAJIT);
            Fixture fixture = new Fixture("app", "probe", ProbeKind.SYNC, "0", "0",
                "int:0");
            Lowered lowered = lower(new Compiled(source.getParent(), orchestrator,
                outputRoot.resolve("app.lua"), new byte[0], new byte[0]), fixture);
            if (lowered == null) {
                System.out.println("RESET_CONTROL_LOWERING_FAILED");
                System.exit(1);
            }
            BlockId body = exportedBodyBlock(lowered, "probe");
            boolean synthetic = false;
            if (body != null) {
                for (SemanticOp op : lowered.opsOfBlock(body)) {
                    if (op.kind() == SemanticOpKind.RETURN && op.origin() != null
                            && op.origin().kind() == SourceOriginKind.SYNTHETIC) {
                        synthetic = true;
                    }
                }
            }
            System.out.println("RESET_CONTROL_SYNTHETIC_RETURN=" + synthetic);
        }
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
        // The epic's fail-closed producer guards keep their identifiers.
        checkEq("CONSTRUCT_UNLOWERED", SemanticLowerer.CONSTRUCT_UNLOWERED,
            "the CONSTRUCT_UNLOWERED guard identifier stays landed");
        checkEq("RETAINED_ABI_DEFERRED", SemanticLowerer.RETAINED_ABI_DEFERRED,
            "the RETAINED_ABI_DEFERRED guard identifier stays landed");
        checkEq("SHARED_EMITTER_COVERAGE", ProductionProjectEmission.SHARED_EMITTER_COVERAGE,
            "the SHARED_EMITTER_COVERAGE guard identifier stays landed");
    }

    // =========================================================================
    // The bounded real-toolchain execution (the release-pipeline contract)
    // =========================================================================

    /** The canonical per-stream cap of the bounded-subprocess contract. */
    private static final int STREAM_CAP_BYTES = 1 << 20;

    /** The canonical truncation marker of the bounded-subprocess contract. */
    private static final String TRUNCATION_MARKER = "\n[STREAM TRUNCATED at 1 MiB]\n";

    /** The canonical per-child deadline of the bounded-subprocess contract. */
    private static final long BOUNDED_PROCESS_BUDGET_MS = 300_000L;

    /**
     * The bounded-subprocess timeout: a hard gate failure, never a skip
     * (an {@link AssertionError}, so no probe-style
     * {@code catch (Exception)} guard can read a hung toolchain as
     * absent).
     */
    private static final class BoundedProcessTimeoutException extends AssertionError {
        private static final long serialVersionUID = 1L;

        BoundedProcessTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * One stream's capped, concurrent drain: the first
     * {@link #STREAM_CAP_BYTES} bytes are retained and the read continues
     * to EOF, so a child that saturates a pipe can never deadlock the
     * harness. After the cap the {@link #TRUNCATION_MARKER} sits at the
     * cut point.
     */
    private static final class CappedDrain implements Runnable {

        private final InputStream stream;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private volatile boolean truncated;

        CappedDrain(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int kept = Math.min(read, STREAM_CAP_BYTES - retained.size());
                    if (kept > 0) {
                        retained.write(buffer, 0, kept);
                    }
                    if (kept < read) {
                        truncated = true;
                    }
                }
            } catch (java.io.IOException ignored) {
                // The stream ends when the child and its descendants are gone.
            }
        }

        String text() {
            String drained = new String(retained.toByteArray(), StandardCharsets.UTF_8);
            return truncated ? drained + TRUNCATION_MARKER : drained;
        }
    }

    /**
     * The bounded post-kill drainage grace: once the owned tree is
     * terminated the pipes reach EOF promptly, so the ownership cleanup
     * after the kill cannot extend the invocation beyond this window.
     */
    private static final long POST_KILL_DRAIN_BUDGET_MS = 5_000L;

    /** The bounded reap window for the direct child. */
    private static final long REAP_BUDGET_MS = 5_000L;

    /**
     * The process-group tools of the bounded runner. Every child starts
     * through {@code setsid}, so the direct child leads its own process
     * group: the runner can kill the whole owned tree — including a
     * descendant that outlives the direct child and is reparented, which
     * {@link Process#descendants()} can no longer see — instead of only
     * the processes that are still parented. Both tools are resolved once,
     * by absolute path, so a child environment's {@code PATH} cannot
     * redirect them; when {@code setsid} is unavailable the runner
     * degrades to descendant-then-child termination inside the same
     * deadline.
     */
    private static final String SETSID_BINARY = firstExecutable("/usr/bin/setsid", "/bin/setsid");
    private static final String KILL_BINARY = firstExecutable("/bin/kill", "/usr/bin/kill");

    private static String firstExecutable(String... candidates) {
        for (String candidate : candidates) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The bounded real-toolchain runner: separate stdout/stderr
     * transcripts, both streams drained concurrently with the canonical
     * 1 MiB cap, the canonical 300000 ms deadline, and on timeout
     * descendants-then-group-then-child forcible termination plus a drain
     * to EOF and a reap, reported as the named hard
     * {@link BoundedProcessTimeoutException}. One monotonic deadline
     * covers the direct child and the drainage of both streams: a
     * descendant that inherited the pipes cannot stretch the invocation
     * past the deadline and is terminated with the rest of the owned tree.
     * The child is owned for the whole call: every path out of the wait —
     * the child's completion, the deadline, an interruption of the waiting
     * thread, or any other failure — terminates the whole owned tree,
     * drains both streams to EOF, and reaps the direct child before the
     * outcome returns or propagates, so no toolchain child and no
     * descendant survives its spawning call.
     */
    private static ProcessOutcome runProcess(Path directory, Map<String, String> env,
            String... command) throws Exception {
        return runBounded(directory, env, BOUNDED_PROCESS_BUDGET_MS, command);
    }

    /**
     * The bounded runner with an explicit budget (the release-run call
     * sites use the canonical default; the gate controls below use a
     * reduced budget so the timeout path stays fast).
     */
    private static ProcessOutcome runBounded(Path directory, Map<String, String> env,
            long budgetMs, String... command) throws Exception {
        boolean ownGroup = SETSID_BINARY != null;
        List<String> argv = new ArrayList<>();
        if (ownGroup) {
            argv.add(SETSID_BINARY);
        }
        argv.addAll(Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(false);
        builder.environment().putAll(env);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        Process process = builder.start();
        CappedDrain stdout = new CappedDrain(process.getInputStream());
        CappedDrain stderr = new CappedDrain(process.getErrorStream());
        Thread stdoutThread = new Thread(stdout, "residual-stdout-" + command[0]);
        Thread stderrThread = new Thread(stderr, "residual-stderr-" + command[0]);
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();
        boolean[] interrupted = {false};
        boolean finished = false;
        boolean drained = false;
        String timeout = null;
        try {
            finished = waitForChild(process, deadlineNanos, interrupted);
            // The same deadline bounds the child and its stream drainage:
            // a reparented descendant that holds the inherited pipes ends
            // the invocation with the named timeout, never with a delay.
            drained = awaitDrains(stdoutThread, stderrThread, deadlineNanos, interrupted);
            if (!finished) {
                timeout = "BOUNDED_PROCESS_TIMEOUT " + command[0];
            } else if (!drained) {
                timeout = "BOUNDED_PROCESS_TIMEOUT " + command[0]
                    + " (the stream drainage exceeded the child deadline)";
            }
        } finally {
            // The finally owns the whole tree: a child that did not finish
            // inside the budget — and one this thread stops waiting for
            // because it was interrupted — is terminated, drained and
            // reaped here, before any outcome propagates.
            terminateTree(process, ownGroup, interrupted);
            awaitDrains(stdoutThread, stderrThread,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(POST_KILL_DRAIN_BUDGET_MS),
                interrupted);
            reap(process, interrupted);
        }
        if (interrupted[0]) {
            Thread.interrupted();
            throw new InterruptedException("interrupted while waiting for " + command[0]);
        }
        if (timeout != null) {
            throw new BoundedProcessTimeoutException(timeout);
        }
        return new ProcessOutcome(process.exitValue(), stdout.text(), stderr.text());
    }

    /**
     * Waits for the direct child inside the one deadline; {@code false}
     * means the deadline expired. An interruption is recorded and left to
     * the caller's finally, which owns the tree before the interruption
     * propagates.
     */
    private static boolean waitForChild(Process process, long deadlineNanos, boolean[] interrupted)
            throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        try {
            return process.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interruptedWait) {
            interrupted[0] = true;
            throw interruptedWait;
        }
    }

    /**
     * Terminates the owned tree in the canonical order — descendants
     * first, then the child's process group (when the runner created one),
     * then the direct child — so nothing outlives the call.
     */
    private static void terminateTree(Process process, boolean ownGroup, boolean[] interrupted) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // A vanished subtree is already gone; the direct child is next.
        }
        if (ownGroup) {
            killProcessGroup(process.pid(), interrupted);
        }
        process.destroyForcibly();
    }

    /**
     * Kills every remaining member of the child's process group from
     * outside the group: the child leads its own group (it was started
     * through {@code setsid}), so its pid is the group id, and a
     * descendant that outlived the direct child — reparented, therefore
     * invisible to {@link Process#descendants()} — dies here. An already
     * empty group yields the helper's not-found status and nothing else.
     * The helper is a containment utility, not a toolchain child: its
     * streams are discarded and its own wait is bounded.
     */
    private static void killProcessGroup(long groupId, boolean[] interrupted) {
        List<String> argv = KILL_BINARY != null
            ? List.of(KILL_BINARY, "-KILL", "--", "-" + groupId)
            : List.of("bash", "-c", "kill -KILL -- -\"$1\"", "residual-kill-group",
                Long.toString(groupId));
        try {
            ProcessBuilder killerBuilder = new ProcessBuilder(argv);
            killerBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            killerBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process killer = killerBuilder.start();
            boolean killed = false;
            try {
                killed = killer.waitFor(POST_KILL_DRAIN_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedKill) {
                interrupted[0] = true;
            }
            if (!killed) {
                killer.destroyForcibly();
            }
        } catch (Exception ignored) {
            // The direct child's destroyForcibly remains the backstop.
        }
    }

    /**
     * Joins both capped drains to EOF inside the deadline; an interruption
     * is recorded and the join retried, so the ownership cleanup completes
     * before an interruption propagates. {@code false} means a drain was
     * still alive at the deadline.
     */
    private static boolean awaitDrains(Thread stdoutThread, Thread stderrThread,
            long deadlineNanos, boolean[] interrupted) {
        boolean drained = true;
        for (Thread thread : new Thread[] {stdoutThread, stderrThread}) {
            while (thread.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    drained = false;
                    break;
                }
                try {
                    thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException interruptedJoin) {
                    interrupted[0] = true;
                }
            }
        }
        return drained;
    }

    /**
     * Reaps the direct child inside a bounded window even when the waiting
     * thread is interrupted; a child that cannot be reaped inside the
     * window is SIGKILLed once more and left to the OS reaper.
     */
    private static void reap(Process process, boolean[] interrupted) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_BUDGET_MS);
        while (process.isAlive()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                process.destroyForcibly();
                return;
            }
            try {
                if (process.waitFor(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)),
                        TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException interruptedReap) {
                interrupted[0] = true;
            }
        }
    }

    /**
     * The bounded-subprocess controls: a finite stderr flood larger than
     * the pipe capacity completes without a deadlock and returns the
     * capped transcript with the truncation marker, and a child that
     * outlives the budget fails hard with the named
     * {@code BOUNDED_PROCESS_TIMEOUT} after the descendants-then-group-
     * then-child kill and the reap.
     */
    private static void testBoundedProcessControls(Path work) throws Exception {
        System.out.println("-- the bounded-subprocess controls: the finite stderr "
            + "flood and the hard timeout --");
        Path script = work.resolve("stderr-flood.lua");
        Files.writeString(script, """
            local chunk = string.rep("f", 1024)
            for _ = 1, 1200 do io.stderr:write(chunk) end
            io.stdout:write("FLOOD-DONE\\n")
            """, StandardCharsets.UTF_8);
        ProcessOutcome flood = runBounded(work, Map.of(), 30_000L, "luajit",
            script.getFileName().toString());
        checkEq(0, flood.exitCode(), "the finite stderr flood: the child exits 0 "
            + "without a pipe deadlock (stdout=" + flood.stdout() + ")");
        checkEq("FLOOD-DONE\n", flood.stdout(), "the finite stderr flood: the stdout "
            + "transcript is intact");
        checkEq(STREAM_CAP_BYTES + TRUNCATION_MARKER.length(), flood.stderr().length(),
            "the finite stderr flood: the stderr transcript retains the 1 MiB cap "
                + "plus the truncation marker");
        check(flood.stderr().endsWith(TRUNCATION_MARKER), "the finite stderr flood: "
            + "the truncation marker sits at the cut point");
        long started = System.nanoTime();
        boolean timedOut = false;
        String timeoutMessage = null;
        try {
            runBounded(work, Map.of(), 1_000L, "bash", "-c", "sleep 30");
        } catch (BoundedProcessTimeoutException timeout) {
            timedOut = true;
            timeoutMessage = timeout.getMessage();
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        check(timedOut, "the hung child with its descendant fails the gate hard "
            + "(BOUNDED_PROCESS_TIMEOUT), never a skip");
        check(timeoutMessage != null && timeoutMessage.startsWith(
                "BOUNDED_PROCESS_TIMEOUT bash"),
            "the hung child names the canonical failure: " + timeoutMessage);
        check(elapsedMs < 20_000L, "the timeout path kills the direct child and its "
            + "descendants and reaps them promptly, got " + elapsedMs + " ms");
    }

    /**
     * The descendant-cleanup controls. First, a direct child that exits
     * inside the budget but leaves a descendant that inherited the pipes:
     * the invocation must end at the one deadline with the named
     * {@code BOUNDED_PROCESS_TIMEOUT} — never block on the descendant's
     * pipe — and kill the descendant. Second, a direct child that exits
     * immediately and leaves a detached descendant whose streams are
     * redirected: the invocation must return the child's outcome inside
     * the budget with the descendant killed before the call returns, so no
     * process survives even when no pipe reports it.
     */
    private static void testBoundedProcessDescendantCleanup(Path work) throws Exception {
        System.out.println("-- the bounded-runner descendant-cleanup controls "
            + "(inherited pipes and redirected streams) --");
        Path inheritedPidFile = work.resolve("inherited-descendant.pid");
        Path inheritedScript = work.resolve("inherited-descendant.sh");
        Files.writeString(inheritedScript, """
            #!/bin/bash
            sleep 30 &
            echo $! > "%s"
            sleep .2
            """.formatted(inheritedPidFile.toAbsolutePath()), StandardCharsets.UTF_8);
        long inheritedStart = System.nanoTime();
        boolean inheritedTimedOut = false;
        String inheritedMessage = null;
        try {
            runBounded(work, Map.of(), 1_000L, "bash",
                inheritedScript.getFileName().toString());
        } catch (BoundedProcessTimeoutException timeout) {
            inheritedTimedOut = true;
            inheritedMessage = timeout.getMessage();
        }
        long inheritedMs = (System.nanoTime() - inheritedStart) / 1_000_000L;
        check(inheritedTimedOut, "the inherited-pipe descendant: the invocation fails "
            + "hard at the one deadline instead of hanging on the descendant's pipe: "
            + inheritedMessage);
        check(inheritedMessage != null && inheritedMessage.startsWith(
                "BOUNDED_PROCESS_TIMEOUT bash"),
            "the inherited-pipe descendant: the canonical failure token: "
                + inheritedMessage);
        check(inheritedMs < 20_000L, "the inherited-pipe descendant: the deadline "
            + "bounds the child and its stream drainage, got " + inheritedMs + " ms");
        long inheritedPid = readPid(inheritedPidFile);
        check(awaitDeath(inheritedPid, 10_000L), "the inherited-pipe descendant: no "
            + "descendant survives the invocation (pid " + inheritedPid + ")");

        Path redirectedScript = work.resolve("redirected-descendant.sh");
        Files.writeString(redirectedScript, """
            #!/bin/bash
            sleep 30 >/dev/null 2>&1 &
            echo $!
            """, StandardCharsets.UTF_8);
        long redirectedStart = System.nanoTime();
        ProcessOutcome redirected = runBounded(work, Map.of(), 5_000L, "bash",
            redirectedScript.getFileName().toString());
        long redirectedMs = (System.nanoTime() - redirectedStart) / 1_000_000L;
        checkEq(0, redirected.exitCode(), "the redirected-stream descendant: the "
            + "direct child's outcome returns inside the one deadline: stdout="
            + redirected.stdout() + " stderr=" + redirected.stderr());
        long redirectedPid = readPid(redirected.stdout());
        check(redirectedPid > 0, "the redirected-stream descendant: the child reports "
            + "the descendant pid: " + redirected.stdout());
        check(redirectedMs < 20_000L, "the redirected-stream descendant: the "
            + "invocation returns promptly, got " + redirectedMs + " ms");
        check(awaitDeath(redirectedPid, 10_000L), "the redirected-stream descendant: "
            + "no descendant survives the invocation (pid " + redirectedPid + ")");
    }

    /** Reads a pid recorded by a control child; -1 when absent or unparseable. */
    private static long readPid(Path file) {
        try {
            return Files.exists(file)
                ? Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim())
                : -1L;
        } catch (Exception unreadable) {
            return -1L;
        }
    }

    /** Reads a pid rendered on a control child's stdout; -1 when unparseable. */
    private static long readPid(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (RuntimeException unparseable) {
            return -1L;
        }
    }

    /**
     * Waits up to the budget for the recorded pid to die and destroys any
     * survivor as a backstop, so a failing control cannot leak a process.
     */
    private static boolean awaitDeath(long pid, long budgetMs) throws InterruptedException {
        if (pid <= 0) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        while (handle != null && handle.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        boolean dead = handle == null || !handle.isAlive();
        if (!dead) {
            handle.destroyForcibly();
        }
        return dead;
    }

    /**
     * The bounded-runner interruption control: a caller interrupted while
     * waiting for a long-running child must leave no child alive. The
     * child records its own pid and becomes the sleep, so the pid the
     * control observes is the direct child itself.
     */
    private static void testBoundedProcessInterruption(Path work) throws Exception {
        System.out.println("-- the bounded-runner interruption control --");
        Path pidFile = work.resolve("interrupted-child.pid");
        Path script = work.resolve("interrupted-child.sh");
        Files.writeString(script, """
            #!/bin/bash
            echo $$ > "%s"
            exec sleep 30
            """.formatted(pidFile.toAbsolutePath()), StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicReference<Throwable> observed =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                runBounded(work, Map.of(), 60_000L, "bash",
                    script.getFileName().toString());
            } catch (Throwable thrown) {
                observed.set(thrown);
            }
        }, "residual-interruption-caller");
        caller.start();
        long startDeadline = System.nanoTime() + 30_000_000_000L;
        while (!Files.exists(pidFile) && System.nanoTime() < startDeadline) {
            Thread.sleep(10L);
        }
        check(Files.exists(pidFile), "the interruption control: the child starts and "
            + "records its pid");
        long pid = Files.exists(pidFile)
            ? Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim())
            : -1L;
        ProcessHandle child = pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
        check(child != null && child.isAlive(), "the interruption control: the child "
            + "is alive before the interrupt");
        caller.interrupt();
        caller.join(30_000L);
        check(!caller.isAlive(), "the interruption control: the interrupted caller "
            + "does not block on the child");
        check(observed.get() instanceof InterruptedException, "the interruption "
            + "control: the waiting caller observes the interruption: " + observed.get());
        if (child != null) {
            long deadDeadline = System.nanoTime() + 10_000_000_000L;
            while (child.isAlive() && System.nanoTime() < deadDeadline) {
                Thread.sleep(20L);
            }
            boolean survived = child.isAlive();
            check(!survived, "the interruption control: no child survives the "
                + "interruption (pid " + pid + ")");
            if (survived) {
                child.destroyForcibly();
            }
        }
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
            testExitStateResetNegativeControl(work);
            testBoundedProcessControls(work);
            testBoundedProcessDescendantCleanup(work);
            testBoundedProcessInterruption(work);
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
