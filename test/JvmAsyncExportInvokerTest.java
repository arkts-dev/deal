package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvocationException;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmAsyncExportInvoker.Result;
import deal.codegen.jvm.JvmBackend;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.codegen.lua.LuaJitAsyncExportInvoker.EnvelopeJson;
import deal.module.CompilationOrchestrator;
import deal.project.StrictManifestParser;
import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ir.ReleaseState;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Verification of the production JVM async-export host invoker
 * (ISSUE-0161, the JVM half of the parent's
 * {@code BackendAsyncExportInvoker}): {@link JvmAsyncExportInvoker} is
 * exercised end-to-end under the real {@code java} toolchain against
 * entry artifacts compiled through the production orchestrator and the
 * emitted reserved host-export entry. The parent D9 scenario (a
 * production {@code async()->null} oracle completing null), the
 * exactly-once init/main/export sequence, every negative selection
 * shape, the completion matcher, and the closed envelope codec all run
 * through the production path. The staged cases (duplicate registry,
 * wrong completion shape, fake launcher envelopes) use the design's
 * staging technique: compiled artifacts cannot produce those shapes, so
 * the staging reuses the production emitted helpers exactly like the
 * LuaJIT sibling's staged entry chunks.
 */
public class JvmAsyncExportInvokerTest {

    private static final String MARKER = "DEAL_ASYNC_EXPORT_RESULT:";

    private static Path tmp;
    private static boolean jvmAvailable;
    private static CompilerInvocation invocation;

    @BeforeClass
    public static void setUpClass() throws IOException {
        jvmAvailable = jvmOnPath();
        tmp = Files.createTempDirectory("deal-jvm-async-invoker-test-");
        invocation = CompilerProfileProvider.resolve(
            ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry());
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

    /** One compiled production JVM entry artifact. */
    private record Compiled(Path outRoot, String className, Path entryClass) {
        Result invoke(String exportName, String returnDescriptor) {
            return new JvmAsyncExportInvoker().invoke(
                new AsyncExportInvocationRequest(entryClass, exportName,
                    returnDescriptor));
        }
    }

    private static JvmAsyncExportInvocationException expectInvocationFailure(
            Path entryArtifact, String exportName, String returnDescriptor) {
        try {
            new JvmAsyncExportInvoker().invoke(
                new AsyncExportInvocationRequest(entryArtifact, exportName,
                    returnDescriptor));
        } catch (JvmAsyncExportInvocationException e) {
            return e;
        }
        fail("expected JvmAsyncExportInvocationException");
        return null;
    }

    /**
     * Compiles one entry module through the production orchestrator path
     * (Backend.JVM, the production v1.2 invocation profile), writes any
     * extra Java sources into the output root, and compiles every
     * emitted artifact with the in-process javac pass the JVM conformance
     * harness uses.
     *
     * @return the compiled entry surface, or null when the fixture was
     *         expected to fail compilation (asserted by the caller)
     */
    private static Compiled compileJvmEntry(Path projectDir,
            String entryFileName, String source, String manifestJson,
            Path outRoot, Map<String, String> extraJava) throws IOException {
        Path srcRoot = projectDir.resolve("src");
        Files.createDirectories(srcRoot);
        Path entrySource = srcRoot.resolve(entryFileName);
        Files.writeString(entrySource, source);
        Map<String, String> externals = null;
        if (manifestJson != null) {
            Files.writeString(projectDir.resolve("deal.json"), manifestJson);
            StrictManifestParser.StrictManifestParseResult parsed =
                StrictManifestParser.parse(
                    projectDir.resolve("deal.json").toString(), manifestJson);
            assertNull("the externals manifest must parse strictly cleanly",
                parsed.failure());
            assertNotNull("the externals manifest must yield a manifest",
                parsed.manifest());
            externals = new LinkedHashMap<>();
            for (Map.Entry<String, deal.project.ExternalEntrySpec> entry
                    : parsed.manifest().externals().entrySet()) {
                Path declaration = projectDir.resolve(
                    entry.getValue().declaration().value());
                externals.put(entry.getKey(),
                    declaration.toAbsolutePath().normalize().toString());
            }
        }
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entrySource.toAbsolutePath(), outRoot, false, false, false,
            false, Backend.JVM, externals,
            List.of(srcRoot.toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, invocation);
        boolean ok = orchestrator.compile();
        assertTrue("production compilation must succeed: "
            + orchestrator.diagnostics(), ok);
        JvmBackend.JvmCodegenResult res = orchestrator.jvmGeneratedResults()
            .get(entrySource.toAbsolutePath().toString());
        assertNotNull("codegen must record the entry result", res);

        if (extraJava != null) {
            for (Map.Entry<String, String> e : extraJava.entrySet()) {
                Files.writeString(outRoot.resolve(e.getKey()), e.getValue());
            }
        }
        List<String> javaFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(outRoot)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                  .sorted()
                  .forEach(p -> javaFiles.add(p.getFileName().toString()));
        }
        StringBuilder javacErr = new StringBuilder();
        boolean javacOk = BackendConformanceTest.compileWithJavac(outRoot,
            javaFiles, javacErr);
        assertTrue("javac must compile the emitted artifacts: " + javacErr,
            javacOk);
        Path entryClass = outRoot.resolve(res.className() + ".class");
        assertTrue("the entry .class artifact must exist",
            Files.isRegularFile(entryClass));
        // javac names a nested $-prefixed class file
        // <Outer>$$<SimpleName>.class: the outer separator $ plus the
        // $-prefixed simple name.
        assertTrue("the reserved host launcher must be compiled next to it",
            Files.isRegularFile(outRoot.resolve(res.className()
                + "$$AsyncExportHost.class")));
        return new Compiled(outRoot, res.className(), entryClass);
    }

    /** Recompiles every .java under the output root (after staged
     * sources were added). */
    private static void recompile(Path outRoot) throws IOException {
        List<String> javaFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(outRoot)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                  .sorted()
                  .forEach(p -> javaFiles.add(p.getFileName().toString()));
        }
        StringBuilder javacErr = new StringBuilder();
        assertTrue("javac must compile the staged sources: " + javacErr,
            BackendConformanceTest.compileWithJavac(outRoot, javaFiles,
                javacErr));
    }

    /** Runs one staged Java class compiled into the fixture output root. */
    private static String runStagedClass(Path outRoot, String className)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("java", "-cp",
            outRoot.toString(), className);
        pb.directory(outRoot.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }

    // =========================================================================
    // Production-path success matrix (parent D9 scenario + value surface)
    // =========================================================================

    @Test
    public void productionCompiledAsyncNullOracleReturnsExactValue()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("smoke-null-out");
        Compiled c = compileJvmEntry(tmp.resolve("smoke-null-src"),
            "oracle_entry.deal", """
                export async function oracle(): null { return null; }
                export function main(): null { return null; }
                """, null, out, null);

        assertEquals("the parent D9 scenario: null completion, byte-exact"
            + " descriptor", new Result.Value("null", "null"),
            c.invoke("oracle", "null"));

        // The reserved entry is generated into the artifact and carries
        // the byte-exact canonical sigs; the host surface is never
        // source-visible (the $ names are unreachable from javaName).
        String generated = Files.readString(
            out.resolve(c.className() + ".java"));
        assertTrue("the artifact emits the reserved host-entry surface",
            generated.contains("Reserved async-export host surface"));
        assertTrue("the registry carries the byte-exact async sig",
            generated.contains("{\"oracle\", \"async()->null\", \"f\","
                + " \"mismatch\"},"));
        assertTrue("the registry carries the byte-exact main sig",
            generated.contains("{\"main\", \"()->null\", \"f\","
                + " \"sync\"},"));
        assertTrue("the artifact emits the reserved launcher class",
            generated.contains("public static final class"
                + " $AsyncExportHost"));
    }

    @Test
    public void productionCompiledAsyncIntReturnsExactValue()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("smoke-int-out");
        Compiled c = compileJvmEntry(tmp.resolve("smoke-int-src"),
            "oracle_entry.deal", """
                export async function fortyTwo(): int { return 42; }
                export function main(): null { return null; }
                """, null, out, null);
        assertEquals(new Result.Value("int", "42"),
            c.invoke("fortyTwo", "int"));
    }

    @Test
    public void productionCompiledAsyncStringReturnsExactQuotedValue()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("smoke-string-out");
        Compiled c = compileJvmEntry(tmp.resolve("smoke-string-src"),
            "oracle_entry.deal", """
                export async function greet(): string { return "hi"; }
                export function main(): null { return null; }
                """, null, out, null);
        assertEquals(new Result.Value("string", "\"hi\""),
            c.invoke("greet", "string"));
    }

    @Test
    public void productionCompiledAsyncIntArrayReturnsRecursiveDescriptorValue()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("smoke-arr-out");
        Compiled c = compileJvmEntry(tmp.resolve("smoke-arr-src"),
            "oracle_entry.deal", """
                export async function values(): int[] { return [1, 2]; }
                export function main(): null { return null; }
                """, null, out, null);
        assertEquals("the [D] descriptor is matched and the array encoded",
            new Result.Value("[int]", "[1,2]"), c.invoke("values", "[int]"));
    }

    // =========================================================================
    // DEAL error propagation (init/main/oracle errors, code and message
    // unchanged; the JVM emitted errors carry no source location)
    // =========================================================================

    @Test
    public void productionCompiledOracleDivisionByZeroPropagatesDealError()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("err-div0-out");
        Compiled c = compileJvmEntry(tmp.resolve("err-div0-src"),
            "oracle_entry.deal", """
                export async function oracle(): int { return 1 / 0; }
                export function main(): null { return null; }
                """, null, out, null);
        assertEquals(new Result.DealError("E8005",
            "integer division by zero", null, null, null),
            c.invoke("oracle", "int"));
    }

    @Test
    public void productionCompiledMainOverflowPropagatesDealError()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("err-main-out");
        Compiled c = compileJvmEntry(tmp.resolve("err-main-src"),
            "oracle_entry.deal", """
                export async function oracle(): int { return 1; }
                export function main(): null {
                  let x: int = 2147483647;
                  x = x + 1;
                  return null;
                }
                """, null, out, null);
        assertEquals("main's DEAL error propagates through the launcher"
            + " with code and message unchanged",
            new Result.DealError("E8004", "int out of safe range",
                null, null, null),
            c.invoke("oracle", "int"));
    }

    // =========================================================================
    // HostInvocationFailure mapping (runtime-pinned reasons, never a DEAL code)
    // =========================================================================

    private void assertCompiledHostFailure(String caseName, String source,
            String exportName, String descriptor, String reason)
            throws IOException {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("matrix-out-" + caseName);
        Compiled c = compileJvmEntry(tmp.resolve("matrix-src-" + caseName),
            "oracle_entry.deal", source, null, out, null);
        Result result = c.invoke(exportName, descriptor);
        assertTrue("expected HostFailure for " + caseName + ", got "
            + result, result instanceof Result.HostFailure);
        assertEquals("the runtime half's pinned reason verbatim, never a"
            + " DEAL code", reason, ((Result.HostFailure) result).reason());
    }

    @Test
    public void productionCompiledMissingExportReturnsPinnedHostFailure()
            throws Exception {
        assertCompiledHostFailure("missing", """
            export async function oracle(): int { return 1; }
            export function main(): null { return null; }
            """, "nope", "int", "missing export 'nope'");
    }

    @Test
    public void productionCompiledSyncExportReturnsPinnedHostFailure()
            throws Exception {
        assertCompiledHostFailure("sync", """
            export function runner(): null { return null; }
            export function main(): null { return null; }
            """, "runner", "null",
            "export 'runner' is sync: expected 'async()->null',"
                + " got '()->null'");
    }

    @Test
    public void productionCompiledParameterizedExportReturnsPinnedHostFailure()
            throws Exception {
        assertCompiledHostFailure("parameterized", """
            export async function takes(x: int): int { return x; }
            export function main(): null { return null; }
            """, "takes", "int",
            "export 'takes' is parameterized: expected 'async()->int',"
                + " got 'async(int)->int'");
    }

    @Test
    public void productionCompiledDescriptorMismatchReturnsPinnedHostFailure()
            throws Exception {
        assertCompiledHostFailure("descriptor_mismatch", """
            export async function oracle(): string { return "x"; }
            export function main(): null { return null; }
            """, "oracle", "int",
            "export 'oracle' signature mismatch: expected 'async()->int',"
                + " got 'async()->string'");
    }

    @Test
    public void productionCompiledClassExportReturnsPinnedHostFailure()
            throws Exception {
        // Exported class metadata is not a function wrapper: the JVM
        // non-production export shape.
        assertCompiledHostFailure("class_export", """
            export class Foo { x: int = 0; }
            export function main(): null { return null; }
            """, "Foo", "null",
            "export 'Foo' is not a function wrapper");
    }

    @Test
    public void nonCanonicalDescriptorMapsToPinnedHostFailureWithoutSpawn()
            throws Exception {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("matrix-out-noncanonical");
        Compiled c = compileJvmEntry(tmp.resolve("matrix-src-noncanonical"),
            "oracle_entry.deal", """
                export async function oracle(): null { return null; }
                export function main(): null { return null; }
                """, null, out, null);
        Result result = c.invoke("oracle", "int[]");
        assertTrue("a non-canonical descriptor maps to the runtime half's"
            + " pinned host-failure reason, never an invoker-side hard"
            + " failure: " + result,
            result instanceof Result.HostFailure);
        assertEquals("return descriptor is not a canonical descriptor: int[]",
            ((Result.HostFailure) result).reason());
    }

    // =========================================================================
    // Staged selection/matcher shapes (compiled artifacts cannot produce
    // them — the design's staging technique, mirroring the LuaJIT staged
    // entry chunks, driving the production emitted helpers)
    // =========================================================================

    @Test
    public void stagedDuplicateRegistryReturnsPinnedHostFailureThroughTheProductionSelector()
            throws Exception {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("staged-dup-out");
        Compiled c = compileJvmEntry(tmp.resolve("staged-dup-src"),
            "oracle_entry.deal", """
                export async function oracle(): int { return 1; }
                export function main(): null { return null; }
                """, null, out, null);
        Files.writeString(out.resolve("StagedDuplicateRunner.java"), """
            public final class StagedDuplicateRunner {
                public static void main(String[] args) {
                    // The duplicated export surface: the same export
                    // name published twice (compiled artifacts can never
                    // produce it — unique export keys — the design's
                    // staging technique).
                    java.lang.String[][] registry = {
                        {"oracle", "async()->int", "f", "mismatch"},
                        {"oracle", "async()->int", "f", "mismatch"},
                    };
                    java.lang.Object[] sel = %s.$asyncExportSelect(
                        registry, "oracle", "int");
                    if (sel[0] != null) {
                        java.lang.System.out.println(
                            %s.$hostFailureEnvelope(
                                (java.lang.String) sel[0]));
                    }
                }
            }
            """.formatted(c.className(), c.className()));
        recompile(out);
        String output = runStagedClass(out, "StagedDuplicateRunner");
        EnvelopeJson.ObjectValue obj = EnvelopeJson.parseObject(
            output.substring(MARKER.length()));
        assertEquals("host-failure",
            ((EnvelopeJson.StringValue) obj.fields().get("status")).text());
        assertEquals("the pinned duplicate reason verbatim",
            "duplicate export 'oracle': the same wrapper appears under"
                + " multiple export keys",
            ((EnvelopeJson.StringValue) obj.fields().get("reason")).text());
    }

    @Test
    public void stagedWrongCompletionMapsToDealErrorEnvelopeThroughTheProductionMatcher()
            throws Exception {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("staged-wrong-out");
        Compiled c = compileJvmEntry(tmp.resolve("staged-wrong-src"),
            "oracle_entry.deal", """
                export async function oracle(): int { return 1; }
                export function main(): null { return null; }
                """, null, out, null);
        Files.writeString(out.resolve("StagedWrongCompletionRunner.java"), """
            public final class StagedWrongCompletionRunner {
                public static void main(String[] args) {
                    try {
                        %s.$check("int", "not an int");
                    } catch (java.lang.Throwable t) {
                        java.lang.Object[] cls =
                            %s.$classifyThrowable(t);
                        java.lang.System.out.println(
                            %s.$dealErrorEnvelope(
                                (java.lang.String) cls[1],
                                (java.lang.String) cls[2]));
                    }
                }
            }
            """.formatted(c.className(), c.className(),
                c.className()));
        recompile(out);
        String output = runStagedClass(out, "StagedWrongCompletionRunner");
        EnvelopeJson.ObjectValue obj = EnvelopeJson.parseObject(
            output.substring(MARKER.length()));
        assertEquals("deal-error",
            ((EnvelopeJson.StringValue) obj.fields().get("status")).text());
        assertEquals("the matcher's E8001 code propagates",
            "E8001", ((EnvelopeJson.StringValue)
                obj.fields().get("code")).text());
        assertEquals("the matcher's pinned mismatch message propagates",
            "expected int, got String",
            ((EnvelopeJson.StringValue) obj.fields().get("message")).text());
        assertNull("a matcher mismatch at the host boundary carries no"
            + " location", obj.fields().get("file"));
    }

    // =========================================================================
    // Exactly-once init/main/oracle in one fresh runtime instance
    // =========================================================================

    @Test
    public void productionCompiledHostProbeRunsExactlyOncePerInvoke()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path probeFile = tmp.resolve("jvm-probe-once.txt");
        Files.writeString(probeFile, "");
        Path projectDir = Files.createDirectories(tmp.resolve("probe-src"));
        Files.writeString(projectDir.resolve("probe.d.deal"), """
            export function mainTouch(): null;
            export function oracleTouch(): null;
            """);
        Path out = tmp.resolve("probe-out");
        String probePath = probeFile.toAbsolutePath().toString()
            .replace("\\", "\\\\");
        Compiled c = compileJvmEntry(projectDir, "oracle_entry.deal", """
            import * as probe from "host/probe"

            export function main(): null {
              probe.mainTouch();
              return null;
            }

            export async function oracle(): null {
              probe.oracleTouch();
              return null;
            }
            """, """
            {
              "languageVersion": "1.2",
              "moduleRoots": ["src"],
              "externals": {
                "host/probe": { "declaration": "probe.d.deal" }
              }
            }
            """, out, Map.of("HostProbe.java", """
            public final class HostProbe {
                // The host class's own initializer observes module load:
                // the entry's import trigger loads this class at DEAL
                // module-initialization time (exactly once per process).
                static {
                    append("load");
                }
                static void append(java.lang.String line) {
                    try (java.io.FileWriter w = new java.io.FileWriter(
                            "%s", true)) {
                        w.write(line + "\\n");
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                public static Object mainTouch() {
                    append("main");
                    return null;
                }
                public static Object oracleTouch() {
                    append("oracle");
                    return null;
                }
            }
            """.formatted(probePath)));

        // Two consecutive invokes each spawn one fresh JVM: one module
        // initialization (the static block), one main() call from the
        // entry dispatch, and one oracle invocation inside it.
        assertEquals(new Result.Value("null", "null"),
            c.invoke("oracle", "null"));
        assertEquals("one load, one main, one oracle in one runtime"
            + " instance", "load\nmain\noracle\n",
            Files.readString(probeFile));

        assertEquals(new Result.Value("null", "null"),
            c.invoke("oracle", "null"));
        assertEquals("the second invoke spawns one fresh process with its"
            + " own exactly-once sequence",
            "load\nmain\noracle\nload\nmain\noracle\n",
            Files.readString(probeFile));
    }

    // =========================================================================
    // Production async function-value oracle (the JVM-side D9 scenario;
    // the bytes-bearing variant is the LuaJIT scenario — the JVM bytes
    // lane is ISSUE-0160's unlanded scope)
    // =========================================================================

    @Test
    public void productionCompiledAsyncFunctionValueOracleCompletesNull()
            throws Exception {
        assumeTrue(jvmAvailable);

        Path out = tmp.resolve("fnval-out");
        Compiled c = compileJvmEntry(tmp.resolve("fnval-src"),
            "oracle_entry.deal", """
                async function echo(s: string): string {
                  return s;
                }
                async function decorate(s: string): string {
                  return "[" + s + "]";
                }
                export function main(): null { return null; }
                export async function oracle(): null {
                  let handlers: (async (s: string) => string)[] =
                    [echo, decorate];
                  let first: string = await handlers[0]("ping");
                  if (first !== "ping") {
                    throw { code: "TEST_FAIL",
                      message: "async function value failed" };
                  }
                  let second: string = await handlers[1](first);
                  if (second !== "[ping]") {
                    throw { code: "TEST_FAIL",
                      message: "async function value content mismatch" };
                  }
                  return null;
                }
                """, null, out, null);
        assertEquals("the oracle assigns and containerizes first-class"
            + " async function values, invokes and awaits one, checks the"
            + " content, and completes null",
            new Result.Value("null", "null"), c.invoke("oracle", "null"));
    }

    @Test
    public void droppingTheAwaitFailsCompilationProvingTheAwaitIsReal()
            throws IOException {
        assumeTrue(jvmAvailable);
        // The no-await mutation control: removing await leaves an
        // un-awaited async call, which the checker rejects (E3014) — the
        // production scenario cannot pass by skipping the await.
        Path out = tmp.resolve("fnval-noawait-out");
        Path srcRoot = Files.createDirectories(tmp.resolve("fnval-noawait-src")
            .resolve("src"));
        Path entrySource = srcRoot.resolve("oracle_entry.deal");
        Files.writeString(entrySource, """
            async function echo(s: string): string {
              return s;
            }
            export function main(): null { return null; }
            export async function oracle(): null {
              let handlers: (async (s: string) => string)[] = [echo];
              handlers[0]("ping");
              return null;
            }
            """);
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entrySource.toAbsolutePath(), out, false, false, false, false,
            Backend.JVM, null, List.of(srcRoot.toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, invocation);
        boolean ok = orchestrator.compile();
        assertFalse("an un-awaited async call must not compile",
            ok);
        assertTrue("the E3014 async-call-without-await diagnostic fires",
            orchestrator.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E3014")));
    }

    @Test
    public void incorrectContentMakesTheOracleFailWithItsOwnDealError()
            throws Exception {
        assumeTrue(jvmAvailable);
        // The incorrect-output mutation control: the awaited handler
        // returns a wrong content, so the oracle's own assertion throws
        // TEST_FAIL and the invoker propagates that exact DEAL error —
        // a wrong completion can never become a successful value.
        Path out = tmp.resolve("fnval-bad-out");
        Compiled c = compileJvmEntry(tmp.resolve("fnval-bad-src"),
            "oracle_entry.deal", """
                async function echo(s: string): string {
                  return "wrong";
                }
                export function main(): null { return null; }
                export async function oracle(): null {
                  let handlers: (async (s: string) => string)[] = [echo];
                  let first: string = await handlers[0]("ping");
                  if (first !== "ping") {
                    throw { code: "TEST_FAIL",
                      message: "async function value failed" };
                  }
                  return null;
                }
                """, null, out, null);
        assertEquals(new Result.DealError("TEST_FAIL",
            "async function value failed", null, null, null),
            c.invoke("oracle", "null"));
    }

    @Test
    public void noCallFunctionValueOracleFailsWithItsOwnDealError()
            throws Exception {
        assumeTrue(jvmAvailable);
        // The no-call mutation control: the oracle assigns and
        // containerizes the first-class async handler but never invokes
        // it, so the call-observed mutation never happens and the
        // oracle's own guard throws TEST_FAIL — the production scenario
        // can never complete null by skipping the call, and the invoker
        // propagates the oracle's exact DEAL error.
        Path out = tmp.resolve("fnval-nocall-out");
        Compiled c = compileJvmEntry(tmp.resolve("fnval-nocall-src"),
            "oracle_entry.deal", """
                async function inc(x: int): int {
                  return x + 1;
                }
                export function main(): null { return null; }
                export async function oracle(): null {
                  let handlers: (async (x: int) => int)[] = [inc];
                  let cell: int[] = [40];
                  if (cell[0] !== 41) {
                    throw { code: "TEST_FAIL",
                      message: "async function value was never invoked" };
                  }
                  return null;
                }
                """, null, out, null);
        assertEquals(new Result.DealError("TEST_FAIL",
            "async function value was never invoked", null, null, null),
            c.invoke("oracle", "null"));
    }

    @Test
    public void jvmBytesOracleIsRejectedWithE6000UntilTheBytesLaneLands()
            throws IOException {
        assumeTrue(jvmAvailable);
        // The E8 boundary pin: the parent's bytes-bearing oracle half on
        // JVM depends on the JVM recursive bytes closure (ISSUE-0160, the
        // int32-bytes lane — E6000 at every bytes site today, pinned by
        // JvmConformanceTest). Until that lane lands, the backend must
        // reject the bytes signature with E6000 — never silently
        // miscompile — while the production scenario above exercises the
        // JVM-supported async function-value shape; the bytes-bearing
        // variant runs on LuaJIT (LuaJitAsyncExportInvokerTest).
        Path out = tmp.resolve("fnval-bytes-out");
        Path srcRoot = Files.createDirectories(tmp.resolve("fnval-bytes-src")
            .resolve("src"));
        Path entrySource = srcRoot.resolve("oracle_entry.deal");
        Files.writeString(entrySource, """
            async function echo(b: bytes): bytes {
              return b;
            }
            export function main(): null { return null; }
            export async function oracle(): null {
              let handlers: (async (b: bytes) => bytes)[] = [echo];
              let buf: bytes = bytes(3);
              let through: bytes = await handlers[0](buf);
              return null;
            }
            """);
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            entrySource.toAbsolutePath(), out, false, false, false, false,
            Backend.JVM, null, List.of(srcRoot.toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, invocation);
        boolean ok = orchestrator.compile();
        assertFalse("the bytes-bearing JVM oracle must not compile until"
            + " the bytes lane lands", ok);
        assertTrue("the E6000 bytes rejection names the unlanded lane",
            orchestrator.diagnostics().stream()
                .anyMatch(d -> d.code().equals("E6000")
                    && d.message().contains("bytes")));
    }

    // =========================================================================
    // Value-representation and interleaving behavior
    // =========================================================================

    @Test
    public void nanCompletionIsAValueRepresentationHardFailure()
            throws Exception {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("nan-out");
        Compiled c = compileJvmEntry(tmp.resolve("nan-src"),
            "oracle_entry.deal", """
                export async function oracle(): number {
                  return 0.0 / 0.0;
                }
                export function main(): null { return null; }
                """, null, out, null);
        JvmAsyncExportInvocationException e = expectInvocationFailure(
            c.entryClass(), "oracle", "number");
        assertTrue("the hard failure names the representation failure: "
            + e.getMessage(),
            e.getMessage().contains("value-representation failure")
                && e.getMessage().contains("cannot encode NaN as JSON"));
        String output = e.capturedOutput();
        assertNotNull("captured process output is attached", output);
        assertTrue("the captured envelope pins the encoder reason: "
            + output, output.contains("representation-failure")
                && output.contains("cannot encode NaN as JSON"));
        assertFalse("an encoding failure is never an expected DEAL code",
            output.contains("deal-error"));
    }

    @Test
    public void userConsoleOutputNeverCorruptsTheEnvelopeFrame()
            throws Exception {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("interleave-out");
        Compiled c = compileJvmEntry(tmp.resolve("interleave-src"),
            "oracle_entry.deal", """
                import * as console from "std/console"
                export async function oracle(): int {
                  console.log("user stdout before envelope");
                  console.error("user stderr line");
                  return 42;
                }
                export function main(): null { return null; }
                """, null, out, null);
        assertEquals("user output on both streams leaves the value"
            + " envelope intact", new Result.Value("int", "42"),
            c.invoke("oracle", "int"));
    }

    // =========================================================================
    // Hard failures: infrastructure, containment, and the fake-launcher
    // codec matrix (the closed envelope schema, exercised through the
    // production invoker against staged launcher classes)
    // =========================================================================

    private static Compiled compileFakeEntry(String name, String launcherBody)
            throws IOException {
        Path out = tmp.resolve(name);
        Files.createDirectories(out);
        Files.writeString(out.resolve("FakeEntry.java"), """
            public final class FakeEntry {
                public static void main(java.lang.String[] args) { }
                public static final class $AsyncExportHost {
                    public static void main(java.lang.String[] args) {
                        %s
                    }
                }
            }
            """.formatted(launcherBody));
        StringBuilder javacErr = new StringBuilder();
        boolean javacOk = BackendConformanceTest.compileWithJavac(out,
            List.of("FakeEntry.java"), javacErr);
        assertTrue("the fake launcher must compile: " + javacErr, javacOk);
        return new Compiled(out, "FakeEntry",
            out.resolve("FakeEntry.class"));
    }

    private static JvmAsyncExportInvocationException invokeFakeEntry(
            String name, String launcherBody) throws IOException {
        Compiled c = compileFakeEntry(name, launcherBody);
        try {
            new JvmAsyncExportInvoker().invoke(new AsyncExportInvocationRequest(
                c.entryClass(), "oracle", "null"));
        } catch (JvmAsyncExportInvocationException e) {
            return e;
        }
        fail("expected JvmAsyncExportInvocationException for: " + name);
        return null;
    }

    @Test
    public void infrastructureFailureEnvelopeIsAHardFailure()
            throws Exception {
        assumeTrue(jvmAvailable);
        JvmAsyncExportInvocationException e = invokeFakeEntry("fake-infra", """
            java.lang.System.out.println(
                "%s{\\"status\\":\\"infrastructure-failure\\",\\"reason\\":\\"boom\\"}");
            java.lang.System.exit(1);
            """.formatted(MARKER));
        assertTrue("an infrastructure-failure envelope is a hard failure"
            + " regardless of the exit code: " + e.getMessage(),
            e.getMessage().contains("infrastructure failure")
                && e.getMessage().contains("boom"));
        String output = e.capturedOutput();
        assertNotNull(output);
        assertTrue(output.contains("boom"));
    }

    @Test
    public void noEnvelopeLineIsAHardFailure() throws Exception {
        assumeTrue(jvmAvailable);
        JvmAsyncExportInvocationException e = invokeFakeEntry("fake-noenv",
            "java.lang.System.out.println(\"some unrelated stdout\");"
                + " java.lang.System.exit(1);");
        assertTrue("a missing envelope is a hard failure: " + e.getMessage(),
            e.getMessage().contains("no " + MARKER + " envelope line"));
        assertTrue("captured stdout is attached", e.capturedOutput()
            .contains("some unrelated stdout"));
    }

    @Test
    public void unknownStatusIsRejected() throws Exception {
        assumeTrue(jvmAvailable);
        JvmAsyncExportInvocationException e = invokeFakeEntry("fake-status",
            "java.lang.System.out.println(\"" + MARKER
                + "{\\\"status\\\":\\\"bogus\\\"}\");");
        assertTrue("an unknown status is rejected: " + e.getMessage(),
            e.getMessage().contains("unknown envelope status: bogus"));
    }

    @Test
    public void unknownFieldIsRejected() throws Exception {
        assumeTrue(jvmAvailable);
        JvmAsyncExportInvocationException e = invokeFakeEntry("fake-field",
            "java.lang.System.out.println(\"" + MARKER
                + "{\\\"status\\\":\\\"value\\\",\\\"descriptor\\\":\\\"null\\\","
                + "\\\"value\\\":\\\"42\\\",\\\"extra\\\":1}\");");
        assertTrue("an unknown field is rejected: " + e.getMessage(),
            e.getMessage().contains("malformed async-export envelope"));
    }

    @Test
    public void malformedEnvelopeJsonIsRejected() throws Exception {
        assumeTrue(jvmAvailable);
        JvmAsyncExportInvocationException e = invokeFakeEntry("fake-json",
            "java.lang.System.out.println(\"" + MARKER + "{not json\");");
        assertTrue("malformed envelope text is rejected: " + e.getMessage(),
            e.getMessage().contains("malformed async-export envelope"));
    }

    @Test
    public void nonIntegralLineFieldIsRejectedEvenThoughTheDecimalParses()
            throws Exception {
        assumeTrue(jvmAvailable);
        JvmAsyncExportInvocationException e = invokeFakeEntry("fake-line",
            "java.lang.System.out.println(\"" + MARKER
                + "{\\\"status\\\":\\\"deal-error\\\",\\\"code\\\":\\\"E8005\\\","
                + "\\\"message\\\":\\\"m\\\",\\\"line\\\":0.10000000000000001}\");");
        assertTrue("a non-integral line is a malformed envelope: "
            + e.getMessage(), e.getMessage().contains("malformed"
            + " async-export envelope"));
    }

    @Test
    public void aValidValueEnvelopeMapsEvenOnANonzeroExit()
            throws Exception {
        assumeTrue(jvmAvailable);
        // The three invocation envelopes map regardless of the exit code:
        // the launcher prints the value envelope and then a hostile exit
        // code the mapping must not reinterpret as infrastructure.
        Compiled c = compileFakeEntry("fake-exit2",
            "java.lang.System.out.println(\"" + MARKER
                + "{\\\"status\\\":\\\"value\\\",\\\"descriptor\\\":\\\"null\\\","
                + "\\\"value\\\":\\\"null\\\"}\"); java.lang.System.exit(2);");
        Result result = new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(c.entryClass(), "oracle",
                "null"));
        assertEquals(new Result.Value("null", "null"), result);
    }

    @Test
    public void missingEntryArtifactFailsBeforeAnySpawn() {
        JvmAsyncExportInvocationException e = expectInvocationFailure(
            tmp.resolve("does_not_exist.class"), "oracle", "null");
        assertTrue("hard-failure reason names the missing artifact: "
            + e.getMessage(), e.getMessage().contains("entry artifact not"
            + " found"));
        assertNull("no process ran, so no output was captured", e.stdout());
        assertNull(e.stderr());
    }

    @Test
    public void nonClassEntryArtifactFailsBeforeAnySpawn() throws Exception {
        Path luaFile = tmp.resolve("entry.lua");
        Files.writeString(luaFile, "return {}");
        JvmAsyncExportInvocationException e = expectInvocationFailure(
            luaFile, "oracle", "null");
        assertTrue("hard-failure reason names the .class requirement: "
            + e.getMessage(),
            e.getMessage().contains("must be a compiled .class file"));
    }

    @Test
    public void entryWithoutHostLauncherFailsBeforeAnySpawn()
            throws Exception {
        assumeTrue(jvmAvailable);
        Path out = tmp.resolve("no-launcher-out");
        Compiled c = compileJvmEntry(tmp.resolve("no-launcher-src"),
            "oracle_entry.deal", """
                export async function oracle(): null { return null; }
                export function main(): null { return null; }
                """, null, out, null);
        Files.delete(out.resolve(c.className()
            + "$$AsyncExportHost.class"));
        JvmAsyncExportInvocationException e = expectInvocationFailure(
            c.entryClass(), "oracle", "null");
        assertTrue("hard-failure reason names the missing host entry: "
            + e.getMessage(), e.getMessage().contains("no async-export"
            + " host entry"));
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

    @Test
    public void hostConstantsPinTheSharedEnvelopeProtocol() {
        assertEquals("both backends share the byte-exact envelope marker",
            LuaJitAsyncExportInvoker.RESULT_PREFIX,
            JvmAsyncExportInvoker.RESULT_PREFIX);
        assertEquals("the reserved host arg is the emitted dispatch"
            + " marker", "$asyncExportHost", JvmAsyncExportInvoker.HOST_ARG);
        assertEquals("the reserved launcher suffix is the emitted nested"
            + " class name", "$AsyncExportHost",
            JvmAsyncExportInvoker.HOST_CLASS_SUFFIX);
        assertEquals("source can never spell the reserved host entry (the"
            + " $ escapes in javaName)", "$dasyncExportHost",
            JvmBackend.javaName("$asyncExportHost"));
        assertEquals("source can never spell the reserved launcher name",
            "$dAsyncExportHost", JvmBackend.javaName("$AsyncExportHost"));
    }

    @Test
    public void concurrentInvokesAreIsolatedProcesses() throws Exception {
        assumeTrue(jvmAvailable);
        Path outA = tmp.resolve("conc-a-out");
        Path outB = tmp.resolve("conc-b-out");
        Compiled a = compileJvmEntry(tmp.resolve("conc-a-src"),
            "oracle_entry.deal", """
                export async function oracle(): int { return 41; }
                export function main(): null { return null; }
                """, null, outA, null);
        Compiled b = compileJvmEntry(tmp.resolve("conc-b-src"),
            "oracle_entry.deal", """
                export async function oracle(): int { return 42; }
                export function main(): null { return null; }
                """, null, outB, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> fa = pool.submit(() -> a.invoke("oracle", "int"));
            Future<Result> fb = pool.submit(() -> b.invoke("oracle", "int"));
            assertEquals(new Result.Value("int", "41"), fa.get());
            assertEquals(new Result.Value("int", "42"), fb.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
