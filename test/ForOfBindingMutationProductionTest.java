package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmNames;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.module.CompilationOrchestrator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.test.conformance.JvmCompilation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The mutable for-of iteration binding production regression (ISSUE-0876).
 *
 * <p>The specification's for-of loop declares one fresh iteration binding
 * per element and captures it by binding, so the body's assignment and a
 * capturing closure's store commit that iteration's own cell while every
 * other capture of the iteration observes the same cell. The JavaScript
 * renderer declared the cell with {@code const} on both arms, so a body
 * assignment raised the native {@code TypeError} reified as E8001
 * ({@code Assignment to constant variable}) while the LuaJIT and JVM
 * artifacts committed the same cell.</p>
 *
 * <p>This suite executes the published artifacts of all three backends.
 * The synchronous fixture runs its own postconditions from {@code main}
 * (visits and totals prove no body was skipped, closure readers and
 * stores prove the mutation and the per-iteration independence, and the
 * traversal controls prove the iterable is evaluated once, follows live
 * contents, and is never redirected). The asynchronous fixture is driven
 * through each lane's production async-export host, and its three
 * completions (36, {@code "a+b+"}, 23) are the exact envelope transcript,
 * so a skipped body, a lost mutation, or a shared binding cell cannot
 * pass. The JavaScript artifact additionally pins the emitted cell shape:
 * both for-of arms declare the iteration cell with {@code let} and no
 * user cell is ever declared {@code const}.</p>
 */
public final class ForOfBindingMutationProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The array and string arms, the closure read and store, the
     * per-iteration cells, and the traversal controls. */
    private static final String SYNC_FIXTURE =
        "control-flow/for-of-let-binding-mutation";

    /** The awaited resumption inside a mutable iteration binding. */
    private static final String ASYNC_FIXTURE =
        "async-await/for-of-let-binding-mutation-async";

    private static final String ENTRY_MODULE = "main";

    /** The array arm's mutable iteration cell: {@code let $g<id> = $e<id>;}. */
    private static final Pattern ARRAY_ARM_CELL = Pattern.compile(
        "^\\s*let \\$g\\d+(?:_\\d+)? = \\$e\\d+;$", Pattern.MULTILINE);

    /** The scalar arm's mutable cell: {@code for (let $g<id> of ...)}. */
    private static final Pattern SCALAR_ARM_CELL = Pattern.compile(
        "for \\(let \\$g\\d+(?:_\\d+)? of \\$rt\\.scalars\\(");

    /** Any user cell declared {@code const}: the defect's exact shape. */
    private static final Pattern CONST_USER_CELL = Pattern.compile(
        "\\bconst \\$g\\d+(?:_\\d+)?");

    /**
     * One asynchronous fixture export driven through a lane's production
     * async-export host: the export name, its completion descriptor, and
     * the JSON text of its required completion value.
     */
    private record AsyncExport(String name, String descriptor,
                               String valueJson) {
    }

    private static final List<AsyncExport> ASYNC_EXPORTS = List.of(
        new AsyncExport("testAsyncArrayMutation", "int", "36"),
        new AsyncExport("testAsyncStringMutation", "string", "\"a+b+\""),
        new AsyncExport("testAsyncClosureMutation", "int", "23"));

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

    private static void checkEq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message + " (expected "
            + expected + ", got " + actual + ")");
    }

    // =========================================================================
    // The fixtures, the sidecars, and the production invocation
    // =========================================================================

    private record Project(Path root, Path src, Path entry, Path out,
                           Path classes) {
    }

    private static String fixtureSource(String fixture) throws Exception {
        String corpusPath = fixture + ".deal";
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(corpusPath), StandardCharsets.UTF_8),
            corpusPath);
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, String source)
            throws Exception {
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", source);
        Path src = root.resolve("src");
        return new Project(root, src, src.resolve(ENTRY_MODULE + ".deal"),
            root.resolve("out"), root.resolve("classes"));
    }

    private static CompilationOrchestrator productionCompile(Project project,
            Backend backend) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            project.entry().toAbsolutePath(), project.out(), false, false, false,
            false, backend, null, List.of(project.src().toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, productionInvocation());
        orchestrator.compile();
        return orchestrator;
    }

    private static void assertProductionEmission(
            CompilationOrchestrator orchestrator, String backend) {
        check(orchestrator.diagnostics().isEmpty(), backend + ": the production "
            + "compile reports zero diagnostics: " + orchestrator.diagnostics());
        checkEq(1, orchestrator.semanticEmissionCount(), backend + ": the "
            + "production arm emits exactly one project artifact");
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr,
                                  boolean stdoutTruncated, boolean stderrTruncated) {
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        return runProcess(command, workDir, Map.of());
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir,
            Map<String, String> environment) throws Exception {
        if (command.get(0).equals("javac")) {
            JvmCompilation.Result compiled =
                JvmCompilation.compile(command, workDir);
            return new ProcessOutcome(compiled.exitCode(), compiled.stdout(),
                compiled.stderr(), false, false);
        }
        BoundedProcess.BoundedResult run =
            BoundedProcess.runCaptured(command, workDir, environment);
        return new ProcessOutcome(run.exitCode(), run.stdout(), run.stderr(),
            run.stdoutTruncated(), run.stderrTruncated());
    }

    private static void assertTranscript(String label, ProcessOutcome run,
            int expectedExit, String expectedStdout, String expectedStderr) {
        check(!run.stdoutTruncated() && !run.stderrTruncated(),
            label + ": the artifact's streams are complete");
        checkEq(expectedExit, run.exitCode(), label + ": the artifact exit "
            + "code: " + run.stdout() + run.stderr());
        checkEq(expectedStdout, run.stdout(), label + ": the artifact stdout");
        checkEq(expectedStderr, run.stderr(), label + ": the artifact stderr");
    }

    private static String absoluteClasspath() {
        StringBuilder resolved = new StringBuilder();
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            if (resolved.length() > 0) {
                resolved.append(File.pathSeparator);
            }
            resolved.append(Path.of(entry).toAbsolutePath().normalize());
        }
        return resolved.toString();
    }

    private static void writeFile(Path root, String relative, String content)
            throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
    // The synchronous fixture: direct assignment, closure read and store,
    // per-iteration cells, and the traversal controls on every lane
    // =========================================================================

    private static void testSyncLuaJit(Path root) throws Exception {
        System.out.println("-- the synchronous fixture's real luajit run --");
        Project project = writeProject(root, fixtureSource(SYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit");
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the production compile "
            + "stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertTranscript("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            0, "", "");
    }

    private static void testSyncJvm(Path root) throws Exception {
        System.out.println("-- the synchronous fixture's published JVM "
            + "artifact under javac and java --");
        Project project = writeProject(root, fixtureSource(SYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JVM);
        assertProductionEmission(orchestrator, "jvm");
        String className = JvmNames.classNameFor(ENTRY_MODULE);
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), "jvm: the production compile stages "
            + "the entry class " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertJvmExecution(project, className, artifact, "jvm");
    }

    private static void testSyncJs(Path root) throws Exception {
        System.out.println("-- the synchronous fixture's published JS artifact "
            + "under node --");
        Project project = writeProject(root, fixtureSource(SYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JS);
        assertProductionEmission(orchestrator, "js");
        Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
        check(Files.isRegularFile(artifact), "js: the production compile stages "
            + "the entry artifact " + artifact);
        check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
            "js: the production compile deploys the runtime");
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertEmitsMutableIterationCells(
            Files.readString(artifact, StandardCharsets.UTF_8), "js");

        assertTranscript("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            0, "", "");
    }

    /**
     * The emitted cell shape: both for-of arms declare the iteration cell
     * with a mutable {@code let} and no user cell is declared {@code const}.
     * The runtime transcripts above are the semantic authority; this pins
     * the one shape whose loss reintroduced E8001.
     */
    private static void assertEmitsMutableIterationCells(String artifact,
            String lane) {
        Matcher array = ARRAY_ARM_CELL.matcher(artifact);
        Matcher scalar = SCALAR_ARM_CELL.matcher(artifact);
        Matcher constant = CONST_USER_CELL.matcher(artifact);
        List<String> constantCells = new ArrayList<>();
        while (constant.find()) {
            constantCells.add(constant.group());
        }
        check(array.find() && array.find(), lane + ": the array for-of arm "
            + "declares two mutable iteration cells (the fixture's own array "
            + "loops)");
        check(scalar.find(), lane + ": the scalar for-of arm declares a mutable "
            + "iteration cell");
        checkEq(List.of(), constantCells, lane + ": no user cell is declared "
            + "const (a const iteration cell is the E8001 defect)");
    }

    private static void assertJvmExecution(Project project, String className,
            Path artifact, String label) throws Exception {
        Files.createDirectories(project.classes());
        String classpath = absoluteClasspath();
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d",
            project.classes().toString(), artifact.toAbsolutePath().toString()),
            project.out());
        checkEq(0, javac.exitCode(), label + ": the emitted artifact compiles "
            + "with javac --release 25 -proc:none: " + javac.stdout()
            + javac.stderr());
        if (javac.exitCode() != 0) {
            return;
        }
        assertTranscript(label, runProcess(List.of("java", "-cp",
                project.classes() + File.pathSeparator + classpath, className),
            project.out()), 0, "", "");
    }

    // =========================================================================
    // The asynchronous fixture: the awaited resumption inside the mutable
    // binding, driven through each lane's production async-export host
    // =========================================================================

    /** The exact envelope line of one checked asynchronous completion. */
    private static String envelope(AsyncExport export) {
        return LuaJitAsyncExportInvoker.RESULT_PREFIX + "{\"status\":\"value\","
            + "\"descriptor\":\"" + export.descriptor() + "\",\"value\":"
            + jsonString(export.valueJson()) + "}";
    }

    private static String expectedAsyncTranscript() {
        StringBuilder transcript = new StringBuilder();
        for (AsyncExport export : ASYNC_EXPORTS) {
            transcript.append(envelope(export)).append('\n');
        }
        return transcript.toString();
    }

    private static String jsonString(String text) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }

    private static void testAsyncLuaJit(Path root) throws Exception {
        System.out.println("-- the asynchronous fixture's completions through "
            + "the production LuaJIT async-export driver --");
        Project project = writeProject(root, fixtureSource(ASYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit");
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        Path driver = Path.of(LuaJitAsyncExportInvoker.DRIVER_RESOURCE)
            .toAbsolutePath().normalize();
        check(Files.isRegularFile(artifact), "luajit: the production compile "
            + "stages the entry chunk " + artifact);
        check(Files.isRegularFile(driver), "luajit: the production async-export "
            + "driver exists: " + driver);
        if (!Files.isRegularFile(artifact) || !Files.isRegularFile(driver)) {
            return;
        }
        StringBuilder transcript = new StringBuilder();
        for (AsyncExport export : ASYNC_EXPORTS) {
            ProcessOutcome run = runProcess(List.of("luajit", driver.toString(),
                    artifact.toAbsolutePath().toString(),
                    project.out().toAbsolutePath().toString(), export.name(),
                    export.descriptor()),
                project.out(), Map.of("DEAL_DEFER_MAIN", "1"));
            check(!run.stdoutTruncated() && !run.stderrTruncated(),
                "luajit: " + export.name() + " streams are complete");
            checkEq(0, run.exitCode(), "luajit: " + export.name()
                + " host exit code: " + run.stdout() + run.stderr());
            checkEq("", run.stderr(), "luajit: " + export.name() + " stderr");
            transcript.append(run.stdout());
        }
        checkEq(expectedAsyncTranscript(), transcript.toString(),
            "luajit: the three awaited completions");
    }

    private static void testAsyncJvm(Path root) throws Exception {
        System.out.println("-- the asynchronous fixture's completions through "
            + "the generated JVM async-export host launcher --");
        Project project = writeProject(root, fixtureSource(ASYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JVM);
        assertProductionEmission(orchestrator, "jvm");
        String className = JvmNames.classNameFor(ENTRY_MODULE);
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), "jvm: the production compile stages "
            + "the entry class " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        Files.createDirectories(project.classes());
        String classpath = absoluteClasspath();
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d",
            project.classes().toString(), artifact.toAbsolutePath().toString()),
            project.out());
        checkEq(0, javac.exitCode(), "jvm: the emitted artifact compiles with "
            + "javac --release 25 -proc:none: " + javac.stdout()
            + javac.stderr());
        if (javac.exitCode() != 0) {
            return;
        }
        // The launcher's binary name is the outer separator $ plus the
        // $-prefixed simple name, exactly what the java launcher resolves.
        String hostClass = className + "$"
            + JvmAsyncExportInvoker.HOST_CLASS_SUFFIX;
        String resolvedClasspath = project.classes() + File.pathSeparator
            + classpath;
        StringBuilder transcript = new StringBuilder();
        for (AsyncExport export : ASYNC_EXPORTS) {
            ProcessOutcome run = runProcess(List.of("java", "-cp",
                    resolvedClasspath, hostClass,
                    JvmAsyncExportInvoker.HOST_ARG, export.name(),
                    export.descriptor()),
                project.out());
            check(!run.stdoutTruncated() && !run.stderrTruncated(),
                "jvm: " + export.name() + " streams are complete");
            checkEq(0, run.exitCode(), "jvm: " + export.name()
                + " host exit code: " + run.stdout() + run.stderr());
            checkEq("", run.stderr(), "jvm: " + export.name() + " stderr");
            transcript.append(run.stdout());
        }
        checkEq(expectedAsyncTranscript(), transcript.toString(),
            "jvm: the three awaited completions");
    }

    /**
     * The JavaScript lane's export drive: the deployed artifact's own
     * zero-arity export carriers are awaited in declaration order and each
     * checked completion is reported in the shared envelope protocol, so
     * the completion values — not just the absence of an error — are the
     * observable postcondition.
     */
    private static String jsAsyncDriver() {
        StringBuilder driver = new StringBuilder();
        driver.append("// Generated by deal.test.ForOfBindingMutationProductionTest.\n")
            .append("// Awaits the asynchronous fixture's exported carriers and\n")
            .append("// reports every checked completion in the shared envelope.\n")
            .append("\"use strict\";\n")
            .append("const $path = require(\"path\");\n")
            .append("const $rt = require($path.resolve(\"./deal/runtime.js\"));\n")
            .append("const $entry = require($path.resolve(\"./")
            .append(ENTRY_MODULE).append(".js\"));\n")
            .append("const $exports = [");
        for (int i = 0; i < ASYNC_EXPORTS.size(); i++) {
            AsyncExport export = ASYNC_EXPORTS.get(i);
            driver.append(i > 0 ? ", " : "")
                .append("[\"").append(export.name()).append("\", \"")
                .append(export.descriptor()).append("\"]");
        }
        driver.append("];\n")
            .append("(async () => {\n")
            .append("  for (const [$name, $descriptor] of $exports) {\n")
            .append("    const $carrier = $entry[$name];\n")
            .append("    if (!$carrier || $carrier.$kind !== \"function\") {\n")
            .append("      throw new Error(\"missing export carrier: \" + $name);\n")
            .append("    }\n")
            .append("    const $value = await $carrier.$f();\n")
            .append("    process.stdout.write(\"")
            .append(LuaJitAsyncExportInvoker.RESULT_PREFIX)
            .append("\" + JSON.stringify({status: \"value\", descriptor:"
                + " $descriptor, value: $jsonText($value)}) + \"\\n\");\n")
            .append("  }\n")
            .append("})().catch(($e) => {\n")
            .append("  const $err = $rt.reifyError($e);\n")
            .append("  process.stdout.write(\"DEAL_ERROR_CODE: \" + $err.code"
                + " + \"\\n\");\n")
            .append("  process.exitCode = 1;\n")
            .append("});\n")
            .append("\nfunction $jsonText($v) {\n")
            .append("  return JSON.stringify($v);\n")
            .append("}\n");
        return driver.toString();
    }

    private static void testAsyncJs(Path root) throws Exception {
        System.out.println("-- the asynchronous fixture's completions through "
            + "the deployed JS artifact under node --");
        Project project = writeProject(root, fixtureSource(ASYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JS);
        assertProductionEmission(orchestrator, "js");
        Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
        check(Files.isRegularFile(artifact), "js: the production compile stages "
            + "the entry artifact " + artifact);
        check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
            "js: the production compile deploys the runtime");
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertEmitsMutableIterationCells(
            Files.readString(artifact, StandardCharsets.UTF_8), "js async");
        Files.writeString(project.out().resolve("ForOfBindingDriver.js"),
            jsAsyncDriver(), StandardCharsets.UTF_8);
        ProcessOutcome run = runProcess(
            List.of("node", "ForOfBindingDriver.js"), project.out());
        assertTranscript("js async", run, 0, expectedAsyncTranscript(),
            "");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== For-Of Binding Mutation Production Test "
            + "(ISSUE-0876) ===\n");
        Path root = Files.createTempDirectory("deal-for-of-binding-");
        try {
            testSyncLuaJit(root.resolve("sync-luajit"));
            testSyncJvm(root.resolve("sync-jvm"));
            testSyncJs(root.resolve("sync-js"));
            testAsyncLuaJit(root.resolve("async-luajit"));
            testAsyncJvm(root.resolve("async-jvm"));
            testAsyncJs(root.resolve("async-js"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== For-Of Binding Mutation Production Test Passed ===");
    }

    private ForOfBindingMutationProductionTest() {
        // Static suite; no instances.
    }
}
