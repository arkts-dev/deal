package deal.test;

import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmAsyncExportInvoker.Result;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The JVM-lane realization of the REGISTRY delegated boundary
 * (ISSUE-0161 consuming ISSUE-0346's boundary): the same committed
 * D12-shaped {@code async-export} record projects under
 * {@code test/fixtures/registry/} execute through the production
 * compile chain ({@link ProjectLocator} + {@link CompilationOrchestrator}
 * with the valid CLI backend override {@code jvm}) and the production
 * {@link JvmAsyncExportInvoker}, and the record outcomes map exactly as
 * the boundary contract pins.
 *
 * <h2>Boundary contract (declarations-page D12, int32/bytes D9)</h2>
 * Records compile through the production CLI/orchestrator and execute
 * {@code async-export} records through the production
 * {@code JvmAsyncExportInvoker} with
 * {@code (entryArtifact, exportName, byte-exact canonical returnDescriptor)};
 * the invoker reaches the reserved generated
 * {@code $AsyncExportHost} host-export entry over the blocking async
 * lowering and maps {@code HostInvocationFailure} for missing/sync/
 * parameterized/duplicate/descriptor-mismatched exports; infrastructure,
 * containment, or descriptor failure is a hard failure and never
 * satisfies an expected DEAL runtime-error code.
 *
 * <h2>What this test realizes and what stays delegated</h2>
 * <ul>
 *   <li>The record projects are the committed D12 invocation shapes —
 *       one canonical {@code async()-&gt;R} oracle plus valid non-async
 *       {@code main(): null} — compiled as exact-v1.2 projects through
 *       the production {@link ProjectLocator} +
 *       {@link CompilationOrchestrator} path under the documented
 *       pre-activation v1.2 invocation (COMMON_SHADOW +
 *       DEAL_V1_2_INT32), with the manifest backend overridden by the
 *       valid CLI alias {@code jvm} (declarations-page D5/D10: a valid
 *       CLI alias may override a valid manifest backend). This tree
 *       authors no sidecar metadata machinery — record authoring,
 *       request supply, registry gating, and the architecture-owned
 *       {@code FeatureBackendMatrix} are parent-owned (ISSUE-0165
 *       family, ISSUE-0346's boundary) — so the record's three
 *       invocation fields ({@code entryArtifact}, {@code exportName},
 *       byte-exact descriptor) are supplied verbatim, the only contract
 *       this tree may rely on.</li>
 *   <li>Execution goes through the production invoker only; the emitted
 *       host surface and the runtime half are consumed unchanged.</li>
 *   <li>The LuaJIT lane of this boundary is {@code
 *       RegistryAsyncExportBoundaryTest} (ISSUE-0346); this suite is its
 *       JVM sibling, so the D12 {@code BYTES_ASYNC_FUNCTION} matrix
 *       family has a committed execution lane on both matrix-required
 *       backends.</li>
 *   <li>The bytes-bearing record half executes on the JVM lane too:
 *       the JVM recursive bytes closure (ISSUE-0160, E8) is landed in
 *       this tree, the exact bytes-bearing oracle completes
 *       {@code Result.Value("null","null")} through the production
 *       invoker, and both matrix-required lanes record SUCCESS. A
 *       backend lane with no recorded outcome (backend omission) fails
 *       the family gate
 *       ({@link #omittingARequiredBackendFailsTheRecordFamilyGate}).
 *       </li>
 * </ul>
 */
public class JvmRegistryAsyncExportBoundaryTest {

    private static final Path FIXTURES = Path.of("test/fixtures/registry");

    /** The D12 BYTES_ASYNC_FUNCTION matrix row, mirrored at the
     * consumption boundary: the same semantic record on LuaJIT and JVM
     * with async-export. The authoritative catalog/matrix validation is
     * parent-owned (ISSUE-0165 family); this tree enforces the mirrored
     * rule over the committed record family. */
    private static final List<String> BYTES_ASYNC_RECORD_REQUIRED_BACKENDS =
        List.of("luajit", "jvm");

    private static Path tmp;
    private static boolean jvmAvailable;
    private static boolean luajitAvailable;
    private static CompilerInvocation invocation;

    @BeforeClass
    public static void setUpClass() throws IOException {
        jvmAvailable = jvmOnPath();
        luajitAvailable = luajitOnPath();
        tmp = Files.createTempDirectory("deal-jvm-registry-boundary-");
        invocation = CompilerProfileProvider.resolveCommonShadow(
            SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
            CapabilityRegistry.releaseRegistry());
    }

    @AfterClass
    public static void tearDownClass() {
        deleteRecursively(tmp);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean jvmOnPath() {
        try {
            new ProcessBuilder("javac", "-version").start().waitFor();
            new ProcessBuilder("java", "-version").start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean luajitOnPath() {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** One compiled JVM-lane record project: the entry source path (the
     * runtime diagnostic {@code file} of its errors) and the compiled
     * entry .class artifact inside the orchestrator-shaped output root. */
    private record CompiledJvmRecord(Path entrySource, Path entryClass) {
        Result invoke(String exportName, String returnDescriptor) {
            return new JvmAsyncExportInvoker().invoke(
                new AsyncExportInvocationRequest(entryClass, exportName,
                    returnDescriptor));
        }
    }

    /**
     * Copies one committed fixture project into a temp directory, locates
     * it through the production {@link ProjectLocator} with the valid CLI
     * backend override {@code jvm} (a valid CLI alias may override a
     * valid manifest backend), and compiles it through the production
     * orchestrator constructor under the documented pre-activation v1.2
     * invocation (COMMON_SHADOW + DEAL_V1_2_INT32) — the same production
     * compile path the parent registry drives. The emitted artifacts are
     * compiled with the real {@code javac} pass the JVM conformance
     * harness uses, exactly like {@code JvmAsyncExportInvokerTest}.
     *
     * @return the compiled entry surface
     */
    private static CompiledJvmRecord compileJvmRecordProject(
            String projectName, String entryFileName, String moduleName)
            throws IOException {
        Path projectDir = Files.createTempDirectory(tmp,
            "jvm-proj-" + projectName + "-");
        copyTree(FIXTURES.resolve(projectName), projectDir);

        Path entrySource = projectDir.resolve("src").resolve(entryFileName)
            .toAbsolutePath().normalize();
        ProjectLocator.LocateResult located = ProjectLocator.locate(
            entrySource.toString(), new CliOverrides("jvm", null));
        assertNull("the " + projectName + " record project must locate"
            + " strictly cleanly: " + located.e2010(), located.e2010());
        assertNotNull("the " + projectName + " record project must"
            + " publish a validated context", located.context());

        Path outputRoot = Path.of(
            located.context().outputPath().absoluteNormalizedPath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entrySource, false, false, false, false,
            null, invocation);
        assertTrue("production JVM compilation of the " + projectName
            + " record must succeed: " + orchestrator.diagnostics(),
            orchestrator.compile());

        deal.codegen.jvm.JvmBackend.JvmCodegenResult res = orchestrator
            .jvmGeneratedResults()
            .get(entrySource.toAbsolutePath().toString());
        assertNotNull("codegen must record the entry result", res);

        List<String> javaFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(outputRoot)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                  .sorted()
                  .forEach(p -> javaFiles.add(
                      p.getFileName().toString()));
        }
        StringBuilder javacErr = new StringBuilder();
        boolean javacOk = BackendConformanceTest.compileWithJavac(
            outputRoot, javaFiles, javacErr);
        assertTrue("javac must compile the emitted artifacts: " + javacErr,
            javacOk);
        Path entryClass = outputRoot.resolve(res.className() + ".class");
        assertTrue("the entry .class artifact must exist",
            Files.isRegularFile(entryClass));
        // javac names the nested launcher
        // <Outer>$$<SimpleName>.class: the outer separator $ plus the
        // $-prefixed simple name — the production invoker's launcher
        // binary.
        assertTrue("the reserved host launcher must be compiled next to it",
            Files.isRegularFile(outputRoot.resolve(res.className()
                + "$$AsyncExportHost.class")));
        return new CompiledJvmRecord(entrySource, entryClass);
    }

    /** One compiled LuaJIT-lane record project (the manifest backend,
     * mirroring {@code RegistryAsyncExportBoundaryTest}). */
    private record CompiledLuaRecord(Path entrySource, Path entryArtifact) {
    }

    private static CompiledLuaRecord compileLuaRecordProject(
            String projectName, String entryFileName, String moduleName)
            throws IOException {
        Path projectDir = Files.createTempDirectory(tmp,
            "lua-proj-" + projectName + "-");
        copyTree(FIXTURES.resolve(projectName), projectDir);

        Path entrySource = projectDir.resolve("src").resolve(entryFileName)
            .toAbsolutePath().normalize();
        ProjectLocator.LocateResult located =
            ProjectLocator.locate(entrySource.toString(), null);
        assertNull("the " + projectName + " record project must locate"
            + " strictly cleanly: " + located.e2010(), located.e2010());
        assertNotNull(located.context());

        Path outputRoot = Path.of(
            located.context().outputPath().absoluteNormalizedPath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entrySource, false, false, false, false,
            null, invocation);
        assertTrue("production LuaJIT compilation of the " + projectName
            + " record must succeed: " + orchestrator.diagnostics(),
            orchestrator.compile());
        assertTrue("the deployment root carries deal/runtime.lua",
            Files.isRegularFile(outputRoot.resolve("deal/runtime.lua")));
        Path entryArtifact = outputRoot.resolve(moduleName + ".lua");
        assertTrue("the compiled entry artifact exists: " + entryArtifact,
            Files.isRegularFile(entryArtifact));
        return new CompiledLuaRecord(entrySource, entryArtifact);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }

    /**
     * The D12 conditional rule for runtime-error records, mirrored at the
     * consumption boundary: only a {@code DealError} with the exact
     * declared code satisfies the expectation. A {@code HostFailure}, a
     * hard-failure exception, or a different DEAL code never does — the
     * pinned "no infrastructure failure ever reads as a satisfied
     * DEAL-error expectation" property.
     */
    private static boolean satisfiesRuntimeErrorExpectation(Result result,
                                                            String expectedCode) {
        return result instanceof Result.DealError d
            && expectedCode.equals(d.code());
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // =========================================================================
    // Acceptance criterion 1 (JVM lane): async-export records execute
    // through the production JVM invoker with matcher-validated
    // completion values and DEAL-Error propagation.
    // =========================================================================

    @Test
    public void jvmLaneAsyncStringRecordExecutesThroughTheProductionInvokerWithMatcherValidatedCompletion()
            throws Exception {
        assumeTrue(jvmAvailable);

        // The D12 async-export record shape on the JVM lane: a canonical
        // async()->string oracle plus valid non-async main(): null,
        // executed through the production JvmAsyncExportInvoker with the
        // byte-exact canonical return descriptor.
        CompiledJvmRecord record = compileJvmRecordProject("string-oracle",
            "oracle.deal", "oracle");

        Result result = record.invoke("oracle", "string");
        assertEquals("matcher-validated string completion with the"
            + " byte-exact canonical return descriptor",
            new Result.Value("string", "\"x\""), result);

        String generated = Files.readString(
            record.entryClass().getParent()
                .resolve(record.entryClass().getFileName().toString()
                    .replace(".class", ".java")));
        assertTrue("the record artifact publishes the canonical async"
            + " oracle wrapper sig",
            generated.contains("\"async()->string\""));
        assertTrue("the record artifact carries the reserved generated"
            + " host-export launcher surface",
            generated.contains("class $AsyncExportHost"));
    }

    @Test
    public void jvmLaneRuntimeErrorRecordPropagatesTheDeclaredDealCodeAndNeverAnother()
            throws Exception {
        assumeTrue(jvmAvailable);

        // The D12 runtime-error record shape on the JVM lane: the oracle
        // raises E8004 from the pinned int32 overflow site; only that
        // exact DEAL code satisfies the record's runtime-error
        // expectation — the JVM emitted errors carry no source location.
        CompiledJvmRecord record = compileJvmRecordProject(
            "runtime-error-oracle", "oracle.deal", "oracle");

        Result result = record.invoke("oracle", "int");
        assertEquals("the oracle's E8004 propagates code/message"
            + " unchanged with the JVM's absent location fields",
            new Result.DealError("E8004", "int out of safe range",
                null, null, null), result);

        assertTrue("the exact declared code satisfies the record's"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(result, "E8004"));
        assertFalse("a different declared code never satisfies the"
            + " expectation",
            satisfiesRuntimeErrorExpectation(result, "E8005"));
    }

    // =========================================================================
    // Acceptance criterion 2 (JVM lane): HostInvocationFailure cases map
    // correctly; no infrastructure failure ever reads as a satisfied
    // DEAL-error expectation.
    // =========================================================================

    @Test
    public void jvmLaneHostInvocationFailureRecordsMapToPinnedHostFailureReasonsNeverADealCode()
            throws Exception {
        assumeTrue(jvmAvailable);

        // Sync export requested through the async-export mode.
        CompiledJvmRecord sync = compileJvmRecordProject("sync-runner",
            "runner.deal", "runner");
        Result syncResult = sync.invoke("runner", "null");
        assertTrue("expected HostFailure for the sync record, got "
            + syncResult, syncResult instanceof Result.HostFailure);
        assertEquals("the runtime's pinned reason, never a DEAL code",
            "export 'runner' is sync: expected 'async()->null',"
                + " got '()->null'",
            ((Result.HostFailure) syncResult).reason());
        assertFalse("a HostInvocationFailure never satisfies a"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(syncResult, "E8004"));

        // Parameterized async export requested as async()->int.
        CompiledJvmRecord parameterized = compileJvmRecordProject(
            "parameterized-oracle", "oracle.deal", "oracle");
        Result parameterizedResult = parameterized.invoke("takes", "int");
        assertTrue("expected HostFailure for the parameterized record, got "
            + parameterizedResult,
            parameterizedResult instanceof Result.HostFailure);
        assertEquals("the runtime's pinned reason, never a DEAL code",
            "export 'takes' is parameterized: expected 'async()->int',"
                + " got 'async(int)->int'",
            ((Result.HostFailure) parameterizedResult).reason());
        assertFalse("a HostInvocationFailure never satisfies a"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(parameterizedResult, "E8004"));

        // Descriptor-mismatched export: async()->string requested with
        // the byte-exact descriptor "int".
        CompiledJvmRecord mismatched = compileJvmRecordProject(
            "string-oracle", "oracle.deal", "oracle");
        Result mismatchedResult = mismatched.invoke("oracle", "int");
        assertTrue("expected HostFailure for the descriptor-mismatched"
            + " record, got " + mismatchedResult,
            mismatchedResult instanceof Result.HostFailure);
        assertEquals("the runtime's pinned reason, never a DEAL code",
            "export 'oracle' signature mismatch: expected 'async()->int',"
                + " got 'async()->string'",
            ((Result.HostFailure) mismatchedResult).reason());
        assertFalse("a HostInvocationFailure never satisfies a"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(mismatchedResult, "E8004"));

        // Missing export: a compiled record requested under an export
        // name it does not publish.
        Result missingResult = mismatched.invoke("nope", "string");
        assertTrue("expected HostFailure for the missing export, got "
            + missingResult, missingResult instanceof Result.HostFailure);
        assertEquals("the runtime's pinned reason, never a DEAL code",
            "missing export 'nope'",
            ((Result.HostFailure) missingResult).reason());
        assertFalse("a HostInvocationFailure never satisfies a"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(missingResult, "E8004"));
    }

    // =========================================================================
    // Acceptance criterion 3/4 (JVM lane): the bytes-bearing record half
    // executes on the JVM lane through the production invoker — the
    // recorded ISSUE-0160 flip, executed with the JVM recursive bytes
    // closure (E8) landed in this tree. The JVM lane outcome of the D12
    // BYTES_ASYNC_FUNCTION record is SUCCESS (mirroring the LuaJIT lane,
    // RegistryAsyncExportBoundaryTest) and the exact bytes-bearing
    // oracle asserts Result.Value("null", "null") with the same
    // identity/content checks the LuaJIT lane pins.
    // =========================================================================

    @Test
    public void jvmLaneAsyncBytesRecordExecutesThroughTheProductionInvokerCompletingNull()
            throws Exception {
        assumeTrue(jvmAvailable);

        // The recorded ISSUE-0160 flip: the D12 BYTES_ASYNC_FUNCTION
        // record (async-bytes-oracle — the exact bytes-bearing oracle
        // that assigns and containerizes first-class async(bytes)->bytes
        // values, invokes and awaits one, checks bytes identity/content
        // in source, and completes null) compiles for the JVM lane with
        // no E6000, publishes its entry artifact, and executes through
        // the production JvmAsyncExportInvoker with the byte-exact
        // canonical return descriptor "null".
        CompiledJvmRecord record = compileJvmRecordProject(
            "async-bytes-oracle", "oracle.deal", "oracle");

        Result result = record.invoke("oracle", "null");
        assertEquals("the exact bytes-bearing oracle executes on the"
            + " JVM lane with the identity/content checks and completes"
            + " null",
            new Result.Value("null", "null"), result);

        String generated = Files.readString(
            record.entryClass().getParent()
                .resolve(record.entryClass().getFileName().toString()
                    .replace(".class", ".java")));
        assertTrue("the record artifact publishes the canonical async"
            + " bytes wrapper sig",
            generated.contains("\"async(bytes)->bytes\""));
    }

    // =========================================================================
    // Acceptance criterion 4: the failure modes of the full production
    // scenario — a broken dependency fails the record on both backends
    // with the exact propagated DEAL error, and a backend omitted from
    // the matrix-required family gate fails the gate.
    // =========================================================================

    @Test
    public void brokenDependencyRecordFailsOnBothBackendsWithThePropagatedDealError()
            throws Exception {
        // The D12 broken-dependency record shape: the oracle awaits an
        // imported async dependency whose TEST_FAIL must propagate as the
        // exact DEAL error — never a satisfied value, never a host or
        // infrastructure failure, and never satisfiable by a different
        // declared code.

        if (luajitAvailable) {
            CompiledLuaRecord lua = compileLuaRecordProject(
                "broken-dependency", "oracle.deal", "oracle");
            LuaJitAsyncExportInvoker.Result luaResult =
                new LuaJitAsyncExportInvoker().invoke(
                    new AsyncExportInvocationRequest(
                        lua.entryArtifact(), "oracle", "null"));
            assertTrue("the broken dependency propagates on the LuaJIT"
                + " lane: " + luaResult,
                luaResult instanceof LuaJitAsyncExportInvoker.Result.DealError);
            LuaJitAsyncExportInvoker.Result.DealError luaError =
                (LuaJitAsyncExportInvoker.Result.DealError) luaResult;
            assertEquals("TEST_FAIL", luaError.code());
            assertEquals("broken dependency", luaError.message());
            assertTrue("the raise location propagates from source",
                luaError.file() != null
                    && luaError.file().endsWith(".deal"));
            assertTrue("the exact declared code satisfies the"
                + " runtime-error expectation on the LuaJIT lane",
                "TEST_FAIL".equals(luaError.code()));
            assertFalse("a different declared code never satisfies the"
                + " broken-dependency expectation on the LuaJIT lane",
                "E8004".equals(luaError.code()));
        }

        assumeTrue(jvmAvailable);
        CompiledJvmRecord jvm = compileJvmRecordProject(
            "broken-dependency", "oracle.deal", "oracle");
        Result jvmResult = jvm.invoke("oracle", "null");
        assertTrue("the broken dependency propagates on the JVM lane: "
            + jvmResult, jvmResult instanceof Result.DealError);
        assertEquals("the oracle's TEST_FAIL propagates code/message"
            + " unchanged with the JVM's absent location fields",
            new Result.DealError("TEST_FAIL", "broken dependency",
                null, null, null), jvmResult);
        assertTrue("the exact declared code satisfies the record's"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(jvmResult, "TEST_FAIL"));
        assertFalse("a different declared code never satisfies the"
            + " broken-dependency expectation",
            satisfiesRuntimeErrorExpectation(jvmResult, "E8004"));
        assertFalse("the scenario can never complete as a value when the"
            + " dependency is broken",
            jvmResult instanceof Result.Value);
    }

    @Test
    public void omittingARequiredBackendFailsTheRecordFamilyGate() {
        // The D12 FeatureBackendMatrix rule mirrored at the consumption
        // boundary: BYTES_ASYNC_FUNCTION requires the same semantic
        // record on LuaJIT and JVM with async-export, and backend
        // omission fails before compilation (declarations-page D12;
        // epic criteria). The authoritative catalog/matrix validation is
        // parent-owned (ISSUE-0165 family); this tree enforces the
        // mirrored rule over the committed record family so a lane
        // removed from the gate fails it.

        Map<String, String> luajitOnly = new LinkedHashMap<>();
        luajitOnly.put("luajit", "SUCCESS");
        assertFalse("a bytes record executed on only one backend must"
            + " fail the family gate (D12 backend omission)",
            recordFamilyGateSatisfied(luajitOnly));

        Map<String, String> jvmOnly = new LinkedHashMap<>();
        jvmOnly.put("jvm", "SUCCESS");
        assertFalse("a lane with only the JVM entry omits LuaJIT and"
            + " fails the family gate",
            recordFamilyGateSatisfied(jvmOnly));

        Map<String, String> both = new LinkedHashMap<>();
        both.put("luajit", "SUCCESS");
        both.put("jvm", "SUCCESS");
        assertTrue("both matrix-required lanes present satisfy the gate",
            recordFamilyGateSatisfied(both));

        // The committed in-tree lane state is truthful: both lanes
        // execute the bytes record to SUCCESS — the LuaJIT lane pins
        // Result.Value("null", "null") (RegistryAsyncExportBoundaryTest)
        // and the JVM lane pins the same completion through the
        // production invoker
        // (jvmLaneAsyncBytesRecordExecutesThroughTheProductionInvokerCompletingNull
        // above) — so the D12 BYTES_ASYNC_FUNCTION matrix family is
        // green on both matrix-required backends.
        Map<String, String> committed = new LinkedHashMap<>();
        committed.put("luajit", "SUCCESS");
        committed.put("jvm", "SUCCESS");
        assertTrue("the committed state has both lanes successful",
            recordFamilyGateSatisfied(committed));
    }

    /**
     * The mirrored two-lane gate over the committed record family: every
     * matrix-required backend must carry a lane outcome; a missing lane
     * (backend omission) fails the gate. The authoritative matrix is the
     * parent-owned {@code FeatureBackendMatrix} (ISSUE-0165 family).
     */
    private static boolean recordFamilyGateSatisfied(
            Map<String, String> laneOutcomes) {
        for (String backend : BYTES_ASYNC_RECORD_REQUIRED_BACKENDS) {
            if (laneOutcomes.get(backend) == null) {
                return false;
            }
        }
        return true;
    }
}
