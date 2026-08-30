package deal.test;

import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.codegen.lua.LuaJitAsyncExportInvoker.EnvelopeJson;
import deal.codegen.lua.LuaJitAsyncExportInvoker.Result;
import deal.codegen.lua.LuaJitAsyncExportInvocationException;
import deal.module.CompilationOrchestrator;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Component-level tests for the production LuaJIT async-export host
 * invoker (ISSUE-0417, luajit-async-export-invoker Verification 1-5):
 * {@link LuaJitAsyncExportInvoker} is exercised end-to-end under real
 * {@code luajit} — production compilation through
 * {@link CompilationOrchestrator} for the smoke case, staged entry
 * chunks plus a deployed {@code deal/runtime.lua} and {@code std/*.lua}
 * for the component cases (the design's own staging technique), and
 * fake drivers for the codec/hard-failure matrix.
 */
public class LuaJitAsyncExportInvokerTest {

    private static final String MARKER = "DEAL_ASYNC_EXPORT_RESULT:";
    private static final String TEMP_PREFIX = "deal-async-export-invoker-";

    private static Path tmp;
    private static Path root;
    private static Path defaultEntry;
    private static boolean luajitAvailable;

    @BeforeClass
    public static void setUpClass() throws IOException {
        luajitAvailable = luajitOnPath();
        tmp = Files.createTempDirectory("deal-async-invoker-test-");
        // Staged deployment root (D5 marker + stdlib surface).
        root = tmp.resolve("root");
        Files.createDirectories(root.resolve("deal"));
        Files.createDirectories(root.resolve("std"));
        Files.copy(Path.of("deal/runtime.lua"),
            root.resolve("deal/runtime.lua"));
        for (String m : new String[] {
                "console", "json", "math", "string", "table", "time"}) {
            Files.copy(Path.of("std", m + ".lua"),
                root.resolve("std").resolve(m + ".lua"));
        }
        Files.createDirectories(root.resolve("entries"));
        defaultEntry = writeEntry("value_null", CHUNK_VALUE_NULL);
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

    private static Path writeEntry(String name, String chunk)
            throws IOException {
        Path p = root.resolve("entries").resolve(name + ".lua");
        Files.writeString(p, chunk);
        return p;
    }

    private static Result invoke(Path entryArtifact, String exportName,
                                 String returnDescriptor) {
        return new LuaJitAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryArtifact, exportName,
                returnDescriptor));
    }

    private static LuaJitAsyncExportInvocationException expectInvocationFailure(
            Path entryArtifact, String exportName, String returnDescriptor) {
        try {
            invoke(entryArtifact, exportName, returnDescriptor);
        } catch (LuaJitAsyncExportInvocationException e) {
            return e;
        }
        fail("expected LuaJitAsyncExportInvocationException");
        return null;
    }

    /** All temp dirs with the invoker's materialization prefix. */
    private static Set<String> invokerTempDirs() throws IOException {
        Set<String> dirs = new TreeSet<>();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(tmpDir)) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)
                        && p.getFileName().toString().startsWith(TEMP_PREFIX)) {
                    dirs.add(p.toAbsolutePath().toString());
                }
            }
        }
        return dirs;
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // =========================================================================
    // Staged entry chunks (hand-written, entry-shaped: require deal.runtime,
    // publish wrapper exports, return exports; main runs at chunk end).
    // =========================================================================

    private static final String CHUNK_VALUE_NULL = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->null", function()
          return __rt.async_start(function()
            return __rt.__NULL
          end)
        end)
        return exports
        """;

    private static final String CHUNK_VALUE_42 = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->int", function()
          return __rt.async_start(function()
            return 42
          end)
        end)
        return exports
        """;

    private static final String CHUNK_DEAL_ERROR_ORACLE = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->int", function()
          return __rt.async_start(function()
            error(__rt._err("E8005", "integer division by zero",
                "staged.deal", 7, 19))
          end)
        end)
        return exports
        """;

    private static final String CHUNK_DEAL_ERROR_MAIN = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.main = __rt.function_("()->null", function()
          error(__rt._err("E8004", "int out of range", "staged.deal", 3, 9))
        end)
        exports.oracle = __rt.function_("async()->int", function()
          return __rt.async_start(function() return 1 end)
        end)
        exports.main.f()
        return exports
        """;

    private static final String CHUNK_DEAL_ERROR_NO_LOCATION = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->int", function()
          return __rt.async_start(function()
            return "not an int"
          end)
        end)
        return exports
        """;

    private static final String CHUNK_EMPTY_EXPORTS = """
        local __rt = require("deal.runtime")
        local exports = {}
        return exports
        """;

    private static final String CHUNK_SYNC = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("()->int", function() return 1 end)
        return exports
        """;

    private static final String CHUNK_PARAMETERIZED = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async(int)->int", function(x)
          return __rt.async_start(function() return x end)
        end)
        return exports
        """;

    private static final String CHUNK_DESCRIPTOR_MISMATCH = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->string", function()
          return __rt.async_start(function() return "x" end)
        end)
        return exports
        """;

    private static final String CHUNK_DUPLICATE = """
        local __rt = require("deal.runtime")
        local exports = {}
        local w = __rt.function_("async()->int", function()
          return __rt.async_start(function() return 1 end)
        end)
        exports.oracle = w
        exports.alias = w
        return exports
        """;

    private static final String CHUNK_NON_WRAPPER = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = 42
        return exports
        """;

    private static final String CHUNK_NON_OPERATION = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->int", function() return 42 end)
        return exports
        """;

    private static final String CHUNK_BYTES = """
        local __rt = require("deal.runtime")
        local exports = {}
        exports.oracle = __rt.function_("async()->bytes", function()
          return __rt.async_start(function()
            return __rt.bytes_new(2)
          end)
        end)
        return exports
        """;

    private static final String CHUNK_INTERLEAVING = """
        local __rt = require("deal.runtime")
        local console = require("std.console")
        console.log.f("user stdout before envelope")
        console.error.f("user stderr line")
        local exports = {}
        exports.oracle = __rt.function_("async()->int", function()
          return __rt.async_start(function() return 42 end)
        end)
        return exports
        """;

    /** One load/main/oracle probe marker line per execution. */
    private static final String CHUNK_PROBE = """
        local __rt = require("deal.runtime")
        local probe = assert(io.open("@PROBE@", "a"))
        probe:write("load\\n")
        probe:close()
        local exports = {}
        exports.main = __rt.function_("()->null", function()
          local p = assert(io.open("@PROBE@", "a"))
          p:write("main\\n")
          p:close()
          return __rt.__NULL
        end)
        exports.oracle = __rt.function_("async()->int", function()
          return __rt.async_start(function()
            local p = assert(io.open("@PROBE@", "a"))
            p:write("oracle\\n")
            p:close()
            return 42
          end)
        end)
        exports.main.f()
        return exports
        """;

    private static String probeChunk(Path probeFile) {
        return CHUNK_PROBE.replace("@PROBE@",
            probeFile.toString().replace("\\", "\\\\"));
    }

    // =========================================================================
    // Production-path smoke: compilation through CompilationOrchestrator,
    // invocation through the production invoker, grep gates.
    // =========================================================================

    @Test
    public void productionCompiledAsyncOracleCompletingNullReturnsExactValue()
            throws Exception {
        assumeTrue(luajitAvailable);

        Path src = Files.createDirectories(tmp.resolve("smoke-src"));
        Path entrySource = src.resolve("oracle_entry.deal");
        Files.writeString(entrySource, """
            export async function oracle(): null { return null; }
            export function main(): null { return null; }
            """);

        // The production compile path (the ModuleSystemTest drive):
        // construct the orchestrator and call compile() with the
        // working-tree root as the stdlib directory, exactly like the
        // CLI (deal/Main.java:221 resolves stdlibDir to the project
        // root when ./std exists; the orchestrator copies
        // <stdlibDir>/std/<module>.lua per module).
        Path out = tmp.resolve("smoke-out");
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entrySource.toAbsolutePath(), out, false, null,
            List.of(src.toAbsolutePath()),
            Path.of("").toAbsolutePath());
        boolean ok = orchestrator.compile();
        assertTrue("production compilation must succeed: "
            + orchestrator.diagnostics(), ok);

        // The compiled entry artifact: the single top-level .lua under
        // the output root (deal/ and std/ live in subdirectories).
        List<Path> artifacts;
        try (Stream<Path> s = Files.list(out)) {
            artifacts = s.filter(p -> p.getFileName().toString()
                .endsWith(".lua")).toList();
        }
        assertEquals("exactly one compiled entry artifact", 1,
            artifacts.size());
        Path entryArtifact = artifacts.get(0);

        Result result = invoke(entryArtifact, "oracle", "null");
        assertEquals("the parent D9 scenario: null completion, byte-exact"
            + " descriptor", new Result.Value("null", "null"), result);

        // Release gates: no generated artifact references the runtime
        // entry or the driver marker; async exports emit the same wrapper
        // shape as sync exports.
        String generated = Files.readString(entryArtifact);
        assertFalse("generated artifacts never call the runtime entry",
            generated.contains("invoke_async_export"));
        assertFalse("generated artifacts never emit the driver marker",
            generated.contains(MARKER));
        assertTrue("async export emits the canonical async wrapper sig",
            generated.contains("\"async()->null\""));
        assertTrue("main emits the sync wrapper sig",
            generated.contains("\"()->null\""));
    }

    // =========================================================================
    // Value mapping (staged component tests)
    // =========================================================================

    @Test
    public void stagedNullCompletionReturnsExactValueJson() throws Exception {
        assumeTrue(luajitAvailable);
        assertEquals(new Result.Value("null", "null"),
            invoke(defaultEntry, "oracle", "null"));
    }

    @Test
    public void stagedScalarCompletionReturnsExactValueJson() throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("value_42", CHUNK_VALUE_42);
        assertEquals(new Result.Value("int", "42"),
            invoke(entry, "oracle", "int"));
    }

    // =========================================================================
    // DEAL error propagation
    // =========================================================================

    @Test
    public void dealErrorFromOperationPropagatesCodeMessageLocationUnchanged()
            throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("deal_error_oracle", CHUNK_DEAL_ERROR_ORACLE);
        Result result = invoke(entry, "oracle", "int");
        assertEquals(new Result.DealError("E8005", "integer division by zero",
            "staged.deal", 7, 19), result);
    }

    @Test
    public void dealErrorFromMainPropagatesVerbatim() throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("deal_error_main", CHUNK_DEAL_ERROR_MAIN);
        Result result = invoke(entry, "oracle", "int");
        assertEquals(new Result.DealError("E8004", "int out of range",
            "staged.deal", 3, 9), result);
    }

    @Test
    public void dealErrorAbsentLocationFieldsStayNull() throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("deal_error_no_location",
            CHUNK_DEAL_ERROR_NO_LOCATION);
        Result result = invoke(entry, "oracle", "int");
        assertEquals("the completion-matcher failure carries no location",
            new Result.DealError("E8001", "expected int", null, null, null),
            result);
    }

    // =========================================================================
    // HostInvocationFailure mapping (runtime-pinned reasons, never a DEAL code)
    // =========================================================================

    private void assertHostFailure(String chunkName, String chunk,
                                   String descriptor, String reason)
            throws IOException {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("host_" + chunkName, chunk);
        Result result = invoke(entry, "oracle", descriptor);
        assertTrue("expected HostFailure for " + chunkName + ", got "
            + result, result instanceof Result.HostFailure);
        assertEquals(reason, ((Result.HostFailure) result).reason());
    }

    @Test
    public void missingExportReturnsPinnedHostFailureReason() throws Exception {
        assertHostFailure("missing", CHUNK_EMPTY_EXPORTS, "int",
            "missing export 'oracle'");
    }

    @Test
    public void syncExportReturnsPinnedHostFailureReason() throws Exception {
        assertHostFailure("sync", CHUNK_SYNC, "int",
            "export 'oracle' is sync: expected 'async()->int',"
                + " got '()->int'");
    }

    @Test
    public void parameterizedExportReturnsPinnedHostFailureReason()
            throws Exception {
        assertHostFailure("parameterized", CHUNK_PARAMETERIZED, "int",
            "export 'oracle' is parameterized: expected 'async()->int',"
                + " got 'async(int)->int'");
    }

    @Test
    public void descriptorMismatchedExportReturnsPinnedHostFailureReason()
            throws Exception {
        assertHostFailure("descriptor_mismatch", CHUNK_DESCRIPTOR_MISMATCH,
            "int",
            "export 'oracle' signature mismatch: expected 'async()->int',"
                + " got 'async()->string'");
    }

    @Test
    public void duplicateExportReturnsPinnedHostFailureReason()
            throws Exception {
        assertHostFailure("duplicate", CHUNK_DUPLICATE, "int",
            "duplicate export 'oracle': the same wrapper appears under"
                + " multiple export keys");
    }

    @Test
    public void nonWrapperExportReturnsPinnedHostFailureReason()
            throws Exception {
        assertHostFailure("non_wrapper", CHUNK_NON_WRAPPER, "int",
            "export 'oracle' is not a function wrapper");
    }

    @Test
    public void nonOperationExportReturnsPinnedHostFailureReason()
            throws Exception {
        assertHostFailure("non_operation", CHUNK_NON_OPERATION, "int",
            "export 'oracle' did not produce an async operation");
    }

    @Test
    public void nonCanonicalDescriptorPassesVerbatimToTheRuntimeHalf()
            throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("host_non_canonical_descriptor",
            CHUNK_EMPTY_EXPORTS);
        Result result = invoke(entry, "oracle", "int[]");
        assertTrue("a non-canonical descriptor maps to the runtime's pinned"
            + " host-failure reason, never a driver-side hard failure: "
            + result, result instanceof Result.HostFailure);
        assertEquals("return descriptor is not a canonical descriptor: int[]",
            ((Result.HostFailure) result).reason());
    }

    // =========================================================================
    // Hard failures: infrastructure / containment / representation
    // =========================================================================

    @Test
    public void missingEntryArtifactFailsBeforeAnySpawn() {
        LuaJitAsyncExportInvocationException e = expectInvocationFailure(
            root.resolve("entries").resolve("does_not_exist.lua"),
            "oracle", "null");
        assertTrue("hard-failure reason names the missing artifact: "
            + e.getMessage(), e.getMessage().contains("entry artifact not"
            + " found"));
        assertNull("no process ran, so no output was captured", e.stdout());
        assertNull(e.stderr());
    }

    @Test
    public void entryWithoutRuntimeMarkerFailsBeforeAnySpawn()
            throws Exception {
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        Path entry = bare.resolve("entry.lua");
        Files.writeString(entry, CHUNK_VALUE_42);
        LuaJitAsyncExportInvocationException e = expectInvocationFailure(
            entry, "oracle", "int");
        assertTrue("hard-failure reason names the missing marker: "
            + e.getMessage(), e.getMessage().contains("no deployment root"
            + " found"));
        assertNull(e.stdout());
        assertNull(e.stderr());
    }

    @Test
    public void missingDriverResourceFailsBeforeAnySpawn() throws Exception {
        LuaJitAsyncExportInvoker invoker = new MissingDriverInvoker();
        try {
            invoker.invoke(new AsyncExportInvocationRequest(defaultEntry,
                "oracle", "null"));
        } catch (LuaJitAsyncExportInvocationException e) {
            assertTrue("hard-failure reason names the missing driver: "
                + e.getMessage(), e.getMessage().contains("driver resource"
                + " not found"));
            assertNull(e.stdout());
            assertNull(e.stderr());
            return;
        }
        fail("expected LuaJitAsyncExportInvocationException");
    }

    @Test
    public void bytesCompletionIsAValueRepresentationHardFailure()
            throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("bytes", CHUNK_BYTES);
        LuaJitAsyncExportInvocationException e = expectInvocationFailure(
            entry, "oracle", "bytes");
        assertTrue("the hard failure names the representation failure: "
            + e.getMessage(),
            e.getMessage().contains("value-representation failure"));
        String output = e.capturedOutput();
        assertNotNull("captured process output is attached", output);
        assertTrue("the captured envelope pins the encoder reason: " + output,
            output.contains("representation-failure")
                && output.contains("unsupported type for JSON encoding:"
                + " bytes"));
        assertFalse("an encoding failure is never an expected DEAL code",
            output.contains("deal-error"));
    }

    @Test
    public void userConsoleOutputNeverCorruptsTheEnvelopeFrame()
            throws Exception {
        assumeTrue(luajitAvailable);
        Path entry = writeEntry("interleaving", CHUNK_INTERLEAVING);
        assertEquals("user output on both streams leaves the value envelope"
            + " intact", new Result.Value("int", "42"),
            invoke(entry, "oracle", "int"));
    }

    // =========================================================================
    // Fake-driver hard-failure matrix (codec and exit-code fail-closedness)
    // =========================================================================

    private static Path writeFakeDriver(String envelopeLine, int exitCode)
            throws IOException {
        Path driver = Files.createTempFile(tmp, "fake-driver-", ".lua");
        Files.writeString(driver,
            "io.write([==[" + envelopeLine + "]==], \"\\n\")\n"
                + "io.flush()\nos.exit(" + exitCode + ")\n");
        return driver;
    }

    private static LuaJitAsyncExportInvocationException invokeFakeDriver(
            String envelopeLine, int exitCode) throws IOException {
        Path driver = writeFakeDriver(envelopeLine, exitCode);
        LuaJitAsyncExportInvoker invoker = new FakeDriverInvoker(driver);
        try {
            invoker.invoke(new AsyncExportInvocationRequest(defaultEntry,
                "oracle", "null"));
        } catch (LuaJitAsyncExportInvocationException e) {
            return e;
        }
        fail("expected LuaJitAsyncExportInvocationException for: "
            + envelopeLine);
        return null;
    }

    @Test
    public void infrastructureFailureEnvelopeIsAHardFailure()
            throws Exception {
        assumeTrue(luajitAvailable);
        LuaJitAsyncExportInvocationException e = invokeFakeDriver(
            MARKER + "{\"status\":\"infrastructure-failure\","
                + "\"reason\":\"boom\"}", 1);
        assertTrue("an infrastructure-failure envelope is a hard failure"
            + " regardless of the exit code: " + e.getMessage(),
            e.getMessage().contains("infrastructure failure")
                && e.getMessage().contains("boom"));
        String output = e.capturedOutput();
        assertNotNull(output);
        assertTrue("captured output carries the envelope reason: " + output,
            output.contains("boom"));
    }

    @Test
    public void noEnvelopeLineIsAHardFailure() throws Exception {
        assumeTrue(luajitAvailable);
        LuaJitAsyncExportInvocationException e = invokeFakeDriver(
            "some unrelated stdout", 1);
        assertTrue("a missing envelope is a hard failure: " + e.getMessage(),
            e.getMessage().contains("no " + MARKER + " envelope line"));
        assertTrue("captured stdout is attached", e.capturedOutput()
            .contains("some unrelated stdout"));
    }

    @Test
    public void unknownStatusIsRejected() throws Exception {
        assumeTrue(luajitAvailable);
        LuaJitAsyncExportInvocationException e = invokeFakeDriver(
            MARKER + "{\"status\":\"bogus\"}", 0);
        assertTrue("an unknown status is rejected: " + e.getMessage(),
            e.getMessage().contains("unknown envelope status: bogus"));
    }

    @Test
    public void unknownFieldIsRejected() throws Exception {
        assumeTrue(luajitAvailable);
        LuaJitAsyncExportInvocationException e = invokeFakeDriver(
            MARKER + "{\"status\":\"value\",\"descriptor\":\"null\","
                + "\"value\":\"42\",\"extra\":1}", 0);
        assertTrue("an unknown field is rejected: " + e.getMessage(),
            e.getMessage().contains("malformed async-export envelope"));
    }

    @Test
    public void malformedEnvelopeJsonIsRejected() throws Exception {
        assumeTrue(luajitAvailable);
        LuaJitAsyncExportInvocationException e = invokeFakeDriver(
            MARKER + "{not json", 0);
        assertTrue("malformed envelope text is rejected: " + e.getMessage(),
            e.getMessage().contains("malformed async-export envelope"));
    }

    @Test
    public void nonIntegralLineFieldIsRejectedEvenThoughTheDecimalParses()
            throws Exception {
        assumeTrue(luajitAvailable);
        // %.17g fractional text parses as a conventional decimal number
        // at the reader level but fails the closed envelope schema for an
        // integer location field.
        LuaJitAsyncExportInvocationException e = invokeFakeDriver(
            MARKER + "{\"status\":\"deal-error\",\"code\":\"E8005\","
                + "\"message\":\"m\",\"line\":0.10000000000000001}", 0);
        assertTrue("a non-integral line is a malformed envelope: "
            + e.getMessage(), e.getMessage().contains("malformed"
            + " async-export envelope"));
    }

    // =========================================================================
    // Codec unit tests (the strict conventional-JSON reader, D6)
    // =========================================================================

    @Test
    public void codecParsesConventionalDecimalNumbers() {
        EnvelopeJson.Value v = EnvelopeJson.parseValue(
            "{\"a\":0.10000000000000001,\"b\":42,\"c\":-3,\"d\":1.5e2}");
        EnvelopeJson.ObjectValue obj = (EnvelopeJson.ObjectValue) v;

        EnvelopeJson.NumberValue a = (EnvelopeJson.NumberValue)
            obj.fields().get("a");
        assertEquals("%.17g fractional text is preserved byte-exactly",
            "0.10000000000000001", a.token());
        assertFalse("fractional tokens are not integral",
            a.isIntegralText());

        EnvelopeJson.NumberValue b = (EnvelopeJson.NumberValue)
            obj.fields().get("b");
        assertEquals("42", b.token());
        assertTrue(b.isIntegralText());
        assertEquals(42L, b.longValue());

        EnvelopeJson.NumberValue c = (EnvelopeJson.NumberValue)
            obj.fields().get("c");
        assertEquals("-3", c.token());
        assertTrue(c.isIntegralText());

        EnvelopeJson.NumberValue d = (EnvelopeJson.NumberValue)
            obj.fields().get("d");
        assertEquals("exponent text is preserved", "1.5e2", d.token());

        assertEquals("a bare conventional decimal parses",
            "0.10000000000000001",
            ((EnvelopeJson.NumberValue)
                EnvelopeJson.parseValue("0.10000000000000001")).token());
    }

    @Test
    public void codecRoundTripsEmbeddedEscapedStrings() {
        String bs = "\\";
        String json = "{\"s\":\"a" + bs + "n" + "b" + bs + "t" + bs + "\""
            + "c" + bs + bs + "d" + bs + "/e" + bs + "u0041" + bs + "u00e9"
            + bs + "ud83d" + bs + "ude00\"}";
        EnvelopeJson.Value v = EnvelopeJson.parseValue(json);
        EnvelopeJson.StringValue s = (EnvelopeJson.StringValue)
            ((EnvelopeJson.ObjectValue) v).fields().get("s");
        assertEquals("escaped strings round-trip byte-exactly (including"
            + " the surrogate pair)", "a\nb\t\"c\\d/eA\u00e9\ud83d\ude00",
            s.text());
    }

    @Test
    public void codecRejectsMalformedText() {
        String[] malformed = {
            "{\"a\":1,}",               // trailing comma
            "{\"a\":1,\"a\":2}",        // duplicate key
            "{\"a\":01}",               // leading zero
            "{\"a\":1.}",               // fraction without digits
            "{\"a\":.5}",               // bare fraction
            "{\"a\":+1}",               // leading plus
            "{\"s\":\"\\x\"}",          // invalid escape
            "{\"s\":\"\\ud800\"}",      // unpaired high surrogate
            "{\"s\":\"\\udc00\"}",      // unpaired low surrogate
            "{\"s\":\"raw\u0001ctl\"}", // raw control character
            "{} trailing",              // trailing content
            "[1]",                      // arrays are outside the schema
            "{\"a\":NaN}",              // non-JSON number
        };
        for (String text : malformed) {
            try {
                EnvelopeJson.parseValue(text);
                fail("expected ParseException for: " + text);
            } catch (EnvelopeJson.ParseException expected) {
            }
        }
    }

    // =========================================================================
    // Exactly one process per invoke / no retry / temp-dir cleanup
    // =========================================================================

    @Test
    public void twoConsecutiveInvokesEachSpawnOneFreshProcessWithExactlyOnceInitMainOracle()
            throws Exception {
        assumeTrue(luajitAvailable);
        Path probe = tmp.resolve("probe_once.txt");
        Path entry = writeEntry("exactly_once", probeChunk(probe));

        Set<String> before = invokerTempDirs();

        assertEquals(new Result.Value("int", "42"),
            invoke(entry, "oracle", "int"));
        assertEquals("one module load, one chunk-end main call, one oracle"
            + " invocation in one runtime instance",
            "load\nmain\noracle\n", Files.readString(probe));
        assertEquals("the per-invocation temp directory is deleted after"
            + " the process exits", before, invokerTempDirs());

        assertEquals(new Result.Value("int", "42"),
            invoke(entry, "oracle", "int"));
        assertEquals("the second invoke spawns one fresh process with its"
            + " own exactly-once sequence",
            "load\nmain\noracle\nload\nmain\noracle\n",
            Files.readString(probe));
        assertEquals("no temp directory leaks across invokes", before,
            invokerTempDirs());
    }

    @Test
    public void concurrentInvokesAreIsolatedProcesses() throws Exception {
        assumeTrue(luajitAvailable);
        Path probeA = tmp.resolve("probe_a.txt");
        Path probeB = tmp.resolve("probe_b.txt");
        Path entryA = writeEntry("conc_a", probeChunk(probeA));
        Path entryB = writeEntry("conc_b", probeChunk(probeB));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> fa = pool.submit(() -> invoke(entryA, "oracle",
                "int"));
            Future<Result> fb = pool.submit(() -> invoke(entryB, "oracle",
                "int"));
            assertEquals(new Result.Value("int", "42"), fa.get());
            assertEquals(new Result.Value("int", "42"), fb.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals("each concurrent invoke owns its own process",
            "load\nmain\noracle\n", Files.readString(probeA));
        assertEquals("each concurrent invoke owns its own process",
            "load\nmain\noracle\n", Files.readString(probeB));
    }

    // =========================================================================
    // API shape pins
    // =========================================================================

    @Test
    public void resultHasExactlyThreePermittedVariants() {
        Set<String> names = Arrays.stream(Result.class.getPermittedSubclasses())
            .map(Class::getSimpleName).collect(Collectors.toSet());
        assertEquals("the sealed result admits exactly the three pinned"
            + " variants and no others", Set.of("Value", "DealError",
            "HostFailure"), names);
    }

    @Test(expected = NullPointerException.class)
    public void requestRejectsNullEntryArtifact() {
        new AsyncExportInvocationRequest(null, "oracle", "null");
    }

    @Test(expected = NullPointerException.class)
    public void requestRejectsNullExportName() {
        new AsyncExportInvocationRequest(Path.of("e.lua"), null, "null");
    }

    @Test(expected = NullPointerException.class)
    public void requestRejectsNullReturnDescriptor() {
        new AsyncExportInvocationRequest(Path.of("e.lua"), "oracle", null);
    }

    @Test
    public void theDriverResourceIsTheOnlyHostSurfaceThatReachesTheRuntimeEntry()
            throws IOException {
        String driver = Files.readString(
            Path.of(LuaJitAsyncExportInvoker.DRIVER_RESOURCE));
        assertTrue("the committed driver owns the runtime-entry call",
            driver.contains("__rt.invoke_async_export"));
        // No production Java source reaches the runtime entry: the
        // invoker's own file may name it only in its contract Javadoc.
        try (Stream<Path> s = Files.walk(Path.of("deal"))) {
            for (Path p : s.toList()) {
                if (!p.getFileName().toString().endsWith(".java")
                        || p.getFileName().toString().equals(
                            "LuaJitAsyncExportInvoker.java")) {
                    continue;
                }
                String text = Files.readString(p);
                assertFalse(p + " must never call the runtime entry",
                    text.contains("invoke_async_export"));
            }
        }
    }

    // =========================================================================
    // Test seams: hide driver lookup sources (D1 hard-failure path)
    // =========================================================================

    /** A seam whose driver lookup returns the named file's bytes. */
    private static final class FakeDriverInvoker
            extends LuaJitAsyncExportInvoker {
        private final Path driver;

        FakeDriverInvoker(Path driver) {
            this.driver = driver;
        }

        @Override
        protected InputStream locateDriverResource() throws IOException {
            return Files.newInputStream(driver);
        }
    }

    /** A seam hiding both driver lookup sources. */
    private static final class MissingDriverInvoker
            extends LuaJitAsyncExportInvoker {
        @Override
        protected InputStream locateDriverResource() {
            return null;
        }
    }
}
