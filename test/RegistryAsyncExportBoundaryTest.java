package deal.test;

import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.codegen.lua.LuaJitAsyncExportInvoker.Result;
import deal.codegen.lua.LuaJitAsyncExportInvocationException;
import deal.module.CompilationOrchestrator;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The LuaJIT-lane realization of the REGISTRY delegated boundary
 * (ISSUE-0346, breakdown BC-000058:REGISTRY): D12-shaped
 * {@code async-export} record projects execute through the production
 * compile chain and the production {@link LuaJitAsyncExportInvoker},
 * and the record outcomes map exactly as the boundary contract pins.
 *
 * <h2>Boundary contract (declarations-page D12, FFI page D8/D12)</h2>
 * Records compile through the production CLI/orchestrator and execute
 * {@code async-export} records through the production
 * {@code LuaJitAsyncExportInvoker} with
 * {@code (entryArtifact, exportName, byte-exact canonical returnDescriptor)};
 * the invoker reaches {@code __rt.invoke_async_export(exports, ...)} and
 * maps {@code HostInvocationFailure} for missing/sync/parameterized/
 * duplicate/descriptor-mismatched exports; infrastructure, containment,
 * or descriptor failure is a hard failure and never satisfies an
 * expected DEAL runtime-error code.
 *
 * <h2>What this test realizes and what stays delegated</h2>
 * <ul>
 *   <li>Record projects are committed under {@code test/fixtures/registry/}
 *       in the D12 invocation shape — one canonical {@code async()->R}
 *       oracle plus valid non-async {@code main(): null} — and compiled
 *       as exact-v1.2 projects through the production
 *       {@link ProjectLocator} + {@link CompilationOrchestrator} path
 *       under the documented pre-activation v1.2 invocation
 *       (COMMON_SHADOW + DEAL_V1_2_INT32). This tree authors no sidecar
 *       metadata machinery: the record's three invocation fields
 *       ({@code entryArtifact}, {@code exportName}, byte-exact
 *       descriptor) are supplied verbatim, which is the only contract
 *       this tree may rely on.</li>
 *   <li>Execution goes through the production invoker only; the
 *       runtime half, the driver, and the emitter are consumed
 *       unchanged (ASYNC_HOST / ASYNC_RT / EL / CUTOVER ownership).</li>
 *   <li>Record authoring machinery, request supply, registry gating,
 *       the FFI/native record corpus under the parent page's gate, and
 *       launcher/supervisor containment are parent-owned (ISSUE-0165
 *       family, ISSUE-0164/0180/0181) and are not built here.</li>
 *   <li>The root gate asserts these executions on every run: this test
 *       is registered in {@code tools/gate-manifest.sh} (TEST_SOURCES +
 *       TEST_MAINS), so the GATE child asserts the record-execution
 *       outcomes from {@code ./run_tests.sh}.</li>
 * </ul>
 */
public class RegistryAsyncExportBoundaryTest {

    private static final Path FIXTURES = Path.of("test/fixtures/registry");

    private static Path tmp;
    private static boolean luajitAvailable;

    @BeforeClass
    public static void setUpClass() throws IOException {
        luajitAvailable = luajitOnPath();
        tmp = Files.createTempDirectory("deal-registry-boundary-");
    }

    @AfterClass
    public static void tearDownClass() {
        deleteRecursively(tmp);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean luajitOnPath() {
        try {
            new ProcessBuilder("luajit", "-v").start().waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * One compiled record project: the entry source path (the runtime
     * diagnostic {@code file} of its errors) and the compiled entry
     * artifact inside the orchestrator-shaped deployment root.
     */
    private record CompiledRecord(Path entrySource, Path entryArtifact) {
    }

    /**
     * Copies one committed fixture project into a temp directory, locates
     * it through the production {@link ProjectLocator}, and compiles it
     * through the production orchestrator constructor under the
     * documented pre-activation v1.2 invocation (COMMON_SHADOW +
     * DEAL_V1_2_INT32) — the same production compile path the parent
     * registry drives.
     */
    private static CompiledRecord compileRecordProject(String projectName,
            String entryFileName, String moduleName) throws IOException {
        Path projectDir = Files.createTempDirectory(tmp, "proj-"
            + projectName + "-");
        copyTree(FIXTURES.resolve(projectName), projectDir);

        Path entrySource = projectDir.resolve("src").resolve(entryFileName)
            .toAbsolutePath().normalize();
        ProjectLocator.LocateResult located =
            ProjectLocator.locate(entrySource.toString(), null);
        assertNull("the " + projectName + " record project must locate"
            + " strictly cleanly: " + located.e2010(), located.e2010());
        assertTrue("the " + projectName + " record project must publish a"
            + " validated context", located.context() != null);

        CompilerInvocation invocation = CompilerProfileProvider
            .resolveCommonShadow(SemanticProfile.DEAL_V1_2_INT32,
                ReleaseState.PRE_ACTIVATION,
                CapabilityRegistry.releaseRegistry());
        Path outputRoot = Path.of(
            located.context().outputPath().absoluteNormalizedPath());
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            located.context(), entrySource, false, false, false, false,
            null, invocation);
        assertTrue("production compilation of the " + projectName
            + " record must succeed: " + orchestrator.diagnostics(),
            orchestrator.compile());

        // The orchestrator-shaped deployment root: the runtime marker the
        // invoker's deployment-root walk requires.
        assertTrue("the deployment root carries deal/runtime.lua",
            Files.isRegularFile(outputRoot.resolve("deal/runtime.lua")));
        Path entryArtifact = outputRoot.resolve(moduleName + ".lua");
        assertTrue("the compiled entry artifact exists: " + entryArtifact,
            Files.isRegularFile(entryArtifact));
        return new CompiledRecord(entrySource, entryArtifact);
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

    private static Result invoke(Path entryArtifact, String exportName,
                                 String returnDescriptor) {
        return new LuaJitAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryArtifact, exportName,
                returnDescriptor));
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
    // Acceptance criterion 1: async-export records execute through the
    // production invoker with matcher-validated completion values and
    // DEAL-Error propagation.
    // =========================================================================

    @Test
    public void d12AsyncBytesRecordExecutesThroughTheProductionInvokerWithMatcherValidatedCompletion()
            throws Exception {
        assumeTrue(luajitAvailable);

        // The D12 BYTES_ASYNC_FUNCTION matrix record shape: an
        // async()->null oracle that constructs a bytes buffer, passes it
        // into a first-class async (b: bytes)=>bytes function value,
        // awaits the result, and completes null.
        CompiledRecord record = compileRecordProject("async-bytes-oracle",
            "oracle.deal", "oracle");

        Result result = invoke(record.entryArtifact(), "oracle", "null");
        assertEquals("matcher-validated null completion with the"
            + " byte-exact canonical return descriptor",
            new Result.Value("null", "null"), result);

        String generated = Files.readString(record.entryArtifact());
        assertTrue("the record artifact publishes the canonical async"
            + " oracle wrapper sig",
            generated.contains("\"async()->null\""));
        assertTrue("the record artifact carries the bytes-bearing async"
            + " function value with its canonical sig",
            generated.contains("\"async(bytes)->bytes\""));
        assertFalse("the record artifact never reaches the runtime entry",
            generated.contains("invoke_async_export"));
    }

    @Test
    public void runtimeErrorRecordPropagatesTheDeclaredDealCodeWithLocationUnchanged()
            throws Exception {
        assumeTrue(luajitAvailable);

        CompiledRecord record = compileRecordProject("runtime-error-oracle",
            "oracle.deal", "oracle");

        Result result = invoke(record.entryArtifact(), "oracle", "int");
        assertEquals("the oracle's E8004 propagates code/message/"
            + "file/line/column unchanged",
            new Result.DealError("E8004", "int out of safe range",
                record.entrySource().toString(), 7, 10), result);

        assertTrue("the exact declared code satisfies the record's"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(result, "E8004"));
        assertFalse("a different declared code never satisfies the"
            + " expectation",
            satisfiesRuntimeErrorExpectation(result, "E8005"));
    }

    // =========================================================================
    // Acceptance criterion 2: HostInvocationFailure cases map correctly;
    // no infrastructure failure ever reads as a satisfied DEAL-error
    // expectation.
    // =========================================================================

    @Test
    public void hostInvocationFailureRecordsMapToPinnedHostFailureReasonsNeverADealCode()
            throws Exception {
        assumeTrue(luajitAvailable);

        // Sync export requested through the async-export mode.
        CompiledRecord sync = compileRecordProject("sync-runner",
            "runner.deal", "runner");
        Result syncResult = invoke(sync.entryArtifact(), "runner", "null");
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
        CompiledRecord parameterized = compileRecordProject(
            "parameterized-oracle", "oracle.deal", "oracle");
        Result parameterizedResult = invoke(parameterized.entryArtifact(),
            "takes", "int");
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
        CompiledRecord mismatched = compileRecordProject("string-oracle",
            "oracle.deal", "oracle");
        Result mismatchedResult = invoke(mismatched.entryArtifact(),
            "oracle", "int");
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
        CompiledRecord oracle = compileRecordProject("async-bytes-oracle",
            "oracle.deal", "oracle");
        Result missingResult = invoke(oracle.entryArtifact(), "nope",
            "null");
        assertTrue("expected HostFailure for the missing export, got "
            + missingResult, missingResult instanceof Result.HostFailure);
        assertEquals("the runtime's pinned reason, never a DEAL code",
            "missing export 'nope'",
            ((Result.HostFailure) missingResult).reason());
        assertFalse("a HostInvocationFailure never satisfies a"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(missingResult, "E8004"));
    }

    @Test
    public void duplicateExportMapsToPinnedHostFailureReason() throws Exception {
        assumeTrue(luajitAvailable);

        // Compiled artifacts cannot produce duplicate wrappers (unique
        // export keys, one wrapper table per key — lua-abi-emission-layer),
        // so the duplicate shape is staged exactly like the invoker's
        // verification matrix: a hand-written entry chunk inside an
        // orchestrator-shaped deployment root, executed through the
        // identical production invoker path.
        Path root = Files.createTempDirectory(tmp, "dup-root-");
        Files.createDirectories(root.resolve("deal"));
        Files.createDirectories(root.resolve("std"));
        Files.copy(Path.of("deal/runtime.lua"),
            root.resolve("deal/runtime.lua"));
        Files.copy(Path.of("std/json.lua"), root.resolve("std/json.lua"));
        Path entry = root.resolve("dup-entry.lua");
        Files.writeString(entry, """
            local __rt = require("deal.runtime")
            local exports = {}
            local w = __rt.function_("async()->int", function()
              return __rt.async_start(function() return 1 end)
            end)
            exports.oracle = w
            exports.alias = w
            return exports
            """);

        Result result = invoke(entry, "oracle", "int");
        assertTrue("expected HostFailure for the duplicate export, got "
            + result, result instanceof Result.HostFailure);
        assertEquals("the runtime's pinned reason, never a DEAL code",
            "duplicate export 'oracle': the same wrapper appears under"
                + " multiple export keys",
            ((Result.HostFailure) result).reason());
        assertFalse("a HostInvocationFailure never satisfies a"
            + " runtime-error expectation",
            satisfiesRuntimeErrorExpectation(result, "E8004"));
    }

    @Test
    public void infrastructureFailureIsAHardFailureNeverASatisfiedDealErrorExpectation()
            throws Exception {
        assumeTrue(luajitAvailable);

        // A missing entry artifact is a hard failure before any spawn.
        try {
            invoke(tmp.resolve("does-not-exist.lua"), "oracle", "null");
            fail("expected LuaJitAsyncExportInvocationException");
        } catch (LuaJitAsyncExportInvocationException e) {
            assertTrue("the hard failure names the missing artifact: "
                + e.getMessage(),
                e.getMessage().contains("entry artifact not found"));
            assertNull("no process ran, so no output was captured",
                e.stdout());
            assertNull(e.stderr());
        }

        // An artifact outside any deployment root (no deal/runtime.lua
        // marker) is a hard failure before any spawn.
        Path bare = Files.createDirectories(tmp.resolve("bare-marker"));
        Path entry = bare.resolve("entry.lua");
        Files.writeString(entry,
            "local __rt = require(\"deal.runtime\")\nreturn {}\n");
        try {
            invoke(entry, "oracle", "null");
            fail("expected LuaJitAsyncExportInvocationException");
        } catch (LuaJitAsyncExportInvocationException e) {
            assertTrue("the hard failure names the missing marker: "
                + e.getMessage(),
                e.getMessage().contains("no deployment root found"));
            assertNull(e.stdout());
            assertNull(e.stderr());
        }

        // The type-level proof: infrastructure failure is a thrown
        // exception, never one of the three Result variants — so a
        // record gate can never read it as a satisfied DEAL-error
        // expectation.
        assertEquals("the sealed result admits exactly the three pinned"
            + " variants and no infrastructure variant",
            Stream.of("Value", "DealError", "HostFailure").sorted()
                .toList(),
            Stream.of(Result.class.getPermittedSubclasses())
                .map(Class::getSimpleName).sorted().toList());
    }
}
