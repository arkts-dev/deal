package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmNames;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.diagnostics.CompilerDiagnostic;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
import deal.semantic.ir.IntrinsicKind;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The catch-binding production regression (ISSUE-0882): the catch
 * variable is an ordinary mutable lexical binding, so a direct
 * catch-variable assignment commits its cell and a closure created in
 * the catch block captures the binding — by binding, with current-value
 * semantics — on all three backends
 * (docs/spec-v1.3.md §Closures and captured variables, §try / catch).
 *
 * <p>All three published artifacts of the synchronous fixture run their own
 * postconditions from {@code main}, and the fixture's completion marker
 * is the sidecar's exact {@code runtime-ok} transcript: capturing after
 * the catch exit, an immediately invoked closure, a nested function
 * declaration, a direct assignment, a store through one closure observed
 * by a sibling capture and by the direct name, the binding's lifetime
 * after its function returned, fresh per-iteration bindings across
 * repeated loop catches, nested catches and block and for-of
 * declarations that shadow it, a shadowing closure parameter that
 * captures nothing, and the alias and ordinary-read controls. The
 * asynchronous fixture is driven through each lane's production
 * async-export host: every awaited resumption's checked completion is
 * the exact envelope transcript, so a capture or store lost across a
 * suspension cannot pass.</p>
 *
 * <p>Each published artifact is inspected beside its execution: a
 * captured catch binding materializes as a fresh cell per catch execution
 * (the LuaJIT table, the JVM box, the JavaScript mutable {@code let}) and
 * the created closures receive that cell — the exact shapes whose absence
 * rejected the source with E6005 or froze the binding at capture time.
 * The semantic oracle is a third consumer of the same lowered IR, so the
 * differential matrix drives it beside the two shared artifacts over the
 * fixture's production project and requires the same trace, effects and
 * terminal; the store-only write (the binding's only occurrence, no read)
 * is compiled and executed on every lane, the exact shape that was
 * rejected with E6005 CONSTRUCT_UNLOWERED.</p>
 */
public final class CatchBindingProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The synchronous behavior matrix and its three-backend oracle. */
    private static final String SYNC_FIXTURE = "closures/catch-binding-closure-capture";

    /** The awaited resumption inside the catch binding. */
    private static final String ASYNC_FIXTURE =
        "async-await/catch-binding-closure-capture-async";

    private static final String ENTRY_MODULE = "main";

    private static final List<String> BACKENDS = List.of("luajit", "jvm", "js");

    /** The LuaJIT catch entry's fresh shared cell: {@code S.b<id>g0 = {__resT}}. */
    private static final Pattern LUAJIT_CATCH_CELL = Pattern.compile(
        "S\\.b(\\d+)g0 = \\{__resT\\}");

    /** The LuaJIT closure factory receiving one catch cell as its capture
     *  (an async closure may also capture the awaited callee's binding). */
    private static final String LUAJIT_CAPTURE_ARG =
        "__factories\\.F\\d+\\([^)]*S\\.b%dg0[^)]*\\)";

    /** The JVM catch entry's fresh shared cell box. */
    private static final Pattern JVM_CATCH_CELL = Pattern.compile(
        "b(\\d+)g0 = new Object\\[\\]\\{[A-Za-z0-9_$.]+\\.reify\\(\\)\\}");

    /** The JVM closure factory receiving one catch cell as its capture
     *  (an async closure may also capture the awaited callee's binding). */
    private static final String JVM_CAPTURE_ARG = "F\\d+\\([^)]*b%dg0[^)]*\\)";

    /** The JavaScript catch clause's mutable cell declaration. */
    private static final Pattern JS_CATCH_CELL = Pattern.compile(
        "let \\$g\\d+(?:_\\d+)? = \\$rt\\.reifyError\\(\\$c\\d+\\);");

    /** Any user cell declared {@code const}: the frozen-binding defect shape. */
    private static final Pattern JS_CONST_USER_CELL = Pattern.compile(
        "\\bconst \\$g\\d+(?:_\\d+)?");

    /**
     * One asynchronous fixture export driven through a lane's production
     * async-export host: the export name, its completion descriptor, and
     * the JSON text of its required completion value.
     */
    private record AsyncExport(String name, String descriptor, String valueJson) {
    }

    private static final List<AsyncExport> ASYNC_EXPORTS = List.of(
        new AsyncExport("testAsyncCaptureAfterAwait", "int", "11"),
        new AsyncExport("testAsyncEarlyCaptureSurvivesAwait", "int", "22"),
        new AsyncExport("testAsyncStoreAcrossAwait", "string", "\"rewritten\""),
        new AsyncExport("testAsyncCaptureAcrossOuterAwait", "int", "44"),
        new AsyncExport("testAsyncLoopCatches", "int", "55"),
        new AsyncExport("testAsyncAsyncClosure", "string", "\"async closure/inner\""));

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

    private static SidecarExpectations.StructuredExpectationSidecar sidecar(
            String fixture) throws Exception {
        Path file = CORPUS.resolve(fixture + ".expect.json");
        check(Files.isRegularFile(file), fixture + ": the fixture carries its "
            + "structured expectation sidecar: " + file);
        return SidecarExpectations.StructuredExpectationSidecar.parse(
            Files.readString(file, StandardCharsets.UTF_8));
    }

    private record ExpectedExecution(byte[] stdout, byte[] stderr, int exitCode) {
    }

    private static ExpectedExecution expectedExecution(String fixture)
            throws Exception {
        SidecarExpectations.StructuredExpectationSidecar sidecar = sidecar(fixture);
        ExpectedExecution pinned = null;
        for (String backend : BACKENDS) {
            SidecarExpectations.RuntimeExpectation expectation =
                sidecar.expectationFor(backend);
            check(expectation instanceof SidecarExpectations.RuntimeExpectation.Executed,
                fixture + ": the sidecar pins the " + backend + " lane");
            if (!(expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed executed)) {
                continue;
            }
            checkEq("runtime-ok", executed.mode(),
                fixture + ": the sidecar's " + backend + " mode");
            ExpectedExecution current = new ExpectedExecution(executed.stdout(),
                executed.stderr(), executed.exitCode());
            if (pinned == null) {
                pinned = current;
            } else {
                checkEq(new String(pinned.stdout(), StandardCharsets.UTF_8),
                    new String(current.stdout(), StandardCharsets.UTF_8),
                    fixture + ": the sidecar pins one transcript on every lane");
                checkEq(pinned.exitCode(), current.exitCode(),
                    fixture + ": the sidecar pins one exit code on every lane");
            }
        }
        check(pinned != null, fixture + ": the sidecar pins an executed lane");
        return pinned;
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

    private static void assertExecution(String label, ProcessOutcome run,
            ExpectedExecution expected) {
        assertTranscript(label, run, expected.exitCode(),
            new String(expected.stdout(), StandardCharsets.UTF_8),
            new String(expected.stderr(), StandardCharsets.UTF_8));
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
    // The captured catch cell's emitted shape
    // =========================================================================

    /**
     * The cell shape of one shared catch binding: the cell is created
     * fresh per catch execution (never a reused direct value cell), and
     * every closure created in the catch block receives that cell as its
     * capture argument — the exact emission whose absence either rejected
     * the source (the missing capture) or froze the captured value.
     */
    private static void assertCatchCellShape(String artifact, String lane,
            Pattern cellPattern, String captureArgFormat) {
        Matcher cell = cellPattern.matcher(artifact);
        Set<Integer> catchCells = new LinkedHashSet<>();
        while (cell.find()) {
            catchCells.add(Integer.valueOf(cell.group(1)));
        }
        check(!catchCells.isEmpty(), lane + ": a captured catch binding "
            + "materializes as a fresh cell per catch execution");
        for (Integer bindingId : catchCells) {
            Pattern captureArg = Pattern.compile(String.format(captureArgFormat,
                bindingId));
            check(captureArg.matcher(artifact).find(), lane + ": the closure "
                + "created in the catch block receives the captured catch cell "
                + "b" + bindingId + "g0");
        }
    }

    /**
     * The JavaScript lane's catch cell: the catch clause declares the
     * binding with a mutable {@code let} (a fresh binding per catch
     * execution) and no user cell is ever declared {@code const}.
     */
    private static void assertJsCatchCellShape(String artifact, String lane) {
        Matcher cells = JS_CATCH_CELL.matcher(artifact);
        List<String> declarations = new ArrayList<>();
        while (cells.find()) {
            declarations.add(cells.group());
        }
        check(!declarations.isEmpty(), lane + ": a catch binding declares a "
            + "mutable catch-clause cell");
        Matcher constant = JS_CONST_USER_CELL.matcher(artifact);
        List<String> constantCells = new ArrayList<>();
        while (constant.find()) {
            constantCells.add(constant.group());
        }
        checkEq(List.of(), constantCells, lane + ": no user cell is declared "
            + "const (a const cell would freeze the catch binding)");
    }

    // =========================================================================
    // The synchronous fixture on every lane
    // =========================================================================

    private static void testSyncLuaJit(Path root, ExpectedExecution expected)
            throws Exception {
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
        assertCatchCellShape(Files.readString(artifact, StandardCharsets.UTF_8),
            "luajit", LUAJIT_CATCH_CELL, LUAJIT_CAPTURE_ARG);
        assertExecution("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    private static void testSyncJvm(Path root, ExpectedExecution expected)
            throws Exception {
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
        assertCatchCellShape(Files.readString(artifact, StandardCharsets.UTF_8),
            "jvm", JVM_CATCH_CELL, JVM_CAPTURE_ARG);
        assertJvmExecution(project, className, artifact, "jvm", expected);
    }

    private static void testSyncJs(Path root, ExpectedExecution expected)
            throws Exception {
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
        assertJsCatchCellShape(Files.readString(artifact, StandardCharsets.UTF_8),
            "js");
        assertExecution("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    private static void assertJvmExecution(Project project, String className,
            Path artifact, String label, ExpectedExecution expected)
            throws Exception {
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
        assertExecution(label, runProcess(List.of("java", "-cp",
                project.classes() + File.pathSeparator + classpath, className),
            project.out()), expected);
    }

    // =========================================================================
    // The asynchronous fixture: the awaited resumptions through each
    // lane's production async-export host
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
        assertCatchCellShape(Files.readString(artifact, StandardCharsets.UTF_8),
            "luajit async", LUAJIT_CATCH_CELL, LUAJIT_CAPTURE_ARG);
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
            "luajit: the six awaited completions");
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
        assertCatchCellShape(Files.readString(artifact, StandardCharsets.UTF_8),
            "jvm async", JVM_CATCH_CELL, JVM_CAPTURE_ARG);
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
            "jvm: the six awaited completions");
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
        driver.append("// Generated by deal.test.CatchBindingProductionTest.\n")
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
                + " $descriptor, value: JSON.stringify($value)}) + \"\\n\");\n")
            .append("  }\n")
            .append("})().catch(($e) => {\n")
            .append("  const $err = $rt.reifyError($e);\n")
            .append("  process.stdout.write(\"DEAL_ERROR_CODE: \" + $err.code"
                + " + \"\\n\");\n")
            .append("  process.exitCode = 1;\n")
            .append("});\n");
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
        assertJsCatchCellShape(Files.readString(artifact, StandardCharsets.UTF_8),
            "js async");
        Files.writeString(project.out().resolve("CatchBindingDriver.js"),
            jsAsyncDriver(), StandardCharsets.UTF_8);
        ProcessOutcome run = runProcess(
            List.of("node", "CatchBindingDriver.js"), project.out());
        assertTranscript("js async", run, 0, expectedAsyncTranscript(), "");
    }

    // =========================================================================
    // The invalid source: the catch variable never leaks past its block
    // =========================================================================

    /**
     * The checker's precise diagnostic is retained for genuinely invalid
     * source: the catch variable is declared by its catch block's own
     * scope, so a read after the block is the ordinary undeclared
     * identifier at its own position and no artifact is published. The
     * lexical registration of the binding inside the catch block never
     * widens that scope.
     */
    private static void testOutOfCatchReference(Path root) throws Exception {
        System.out.println("-- the catch variable stays scoped to its catch "
            + "block --");
        String source = """
            export function main(): null {
              try { throw {}; } catch (e) { }
              if (e.message !== "") { return null; }
              return null;
            }
            """;
        Project project = writeProject(root, source);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JS);
        checkEq(1, orchestrator.diagnostics().size(), "js: the out-of-catch "
            + "reference reports exactly one diagnostic");
        if (orchestrator.diagnostics().size() != 1) {
            return;
        }
        CompilerDiagnostic diagnostic = orchestrator.diagnostics().get(0);
        checkEq("E2001", diagnostic.code(), "js: the out-of-catch reference keeps "
            + "the precise undeclared-identifier diagnostic");
        checkEq("Undeclared identifier 'e'", diagnostic.message(),
            "js: the diagnostic names the undeclared catch variable");
        checkEq(3, diagnostic.range().startLine(),
            "js: the diagnostic carries the reference's own line");
        checkEq(7, diagnostic.range().startColumn(),
            "js: the diagnostic carries the reference's own column");
        checkEq(0, orchestrator.semanticEmissionCount(), "js: the rejected source "
            + "publishes no artifact");
    }

    // =========================================================================
    // The store-only catch variable: the write is the binding's only use
    // =========================================================================

    /**
     * The issue's second minimal manifestation with the assignment as the
     * catch variable's only occurrence: a bare identifier write target is
     * the declared binding's own address chain ({@code BINDING_STORE}), so
     * the module compiles — the coverage detector asks for no
     * {@code IDENTIFIER} read — and the published artifact of every lane
     * runs. The synchronous fixture's direct assignment is followed by
     * reads, so this is the exact shape that the missing binding rejected
     * with E6005 CONSTRUCT_UNLOWERED.
     */
    private static final String STORE_ONLY_SOURCE = """
        export function main(): null {
          try { throw { code: "E_STORE", message: "caught" }; }
          catch (e) { e = { code: "E_STORE2", message: "stored" }; }
          return null;
        }
        """;

    private static Project compileStoreOnly(Path root, Backend backend,
            String label) throws Exception {
        Project project = writeProject(root, STORE_ONLY_SOURCE);
        assertProductionEmission(productionCompile(project, backend), label);
        return project;
    }

    private static void testStoreOnlyDirectAssignment(Path root) throws Exception {
        System.out.println("-- the store-only catch variable compiles and runs "
            + "on every lane --");
        ExpectedExecution expected = new ExpectedExecution(new byte[0],
            new byte[0], 0);

        Project luajit = compileStoreOnly(root.resolve("luajit"),
            Backend.LUAJIT, "store-only luajit");
        Path luaArtifact = luajit.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(luaArtifact), "store-only luajit: the "
            + "production compile stages the entry chunk " + luaArtifact);
        if (Files.isRegularFile(luaArtifact)) {
            assertExecution("store-only luajit", runProcess(
                List.of("luajit", ENTRY_MODULE + ".lua"), luajit.out()), expected);
        }

        Project jvm = compileStoreOnly(root.resolve("jvm"), Backend.JVM,
            "store-only jvm");
        String className = JvmNames.classNameFor(ENTRY_MODULE);
        Path jvmArtifact = jvm.out().resolve(className + ".java");
        check(Files.isRegularFile(jvmArtifact), "store-only jvm: the production "
            + "compile stages the entry class " + jvmArtifact);
        if (Files.isRegularFile(jvmArtifact)) {
            assertJvmExecution(jvm, className, jvmArtifact, "store-only jvm",
                expected);
        }

        Project js = compileStoreOnly(root.resolve("js"), Backend.JS,
            "store-only js");
        Path jsArtifact = js.out().resolve(ENTRY_MODULE + ".js");
        check(Files.isRegularFile(jsArtifact), "store-only js: the production "
            + "compile stages the entry artifact " + jsArtifact);
        if (Files.isRegularFile(jsArtifact)) {
            assertExecution("store-only js", runProcess(
                List.of("node", ENTRY_MODULE + ".js"), js.out()), expected);
        }
    }

    // =========================================================================
    // The semantic oracle: the third consumer of the same lowered IR
    // =========================================================================

    /**
     * The production lowering of one compiled project: the compile's own
     * invocation, checked project, manifests and declaration surface are
     * the production arm's inputs, so the differential drives exactly the
     * unit the published artifacts were emitted from.
     */
    private static SemanticLowerer.ProjectLoweringResult productionLowering(
            CompilationOrchestrator orchestrator) {
        CheckedProjectBuildResult checked = orchestrator.checkedProject();
        return SemanticLowerer.lowerProject(orchestrator.invocation(),
            checked.input(), checked.index(),
            orchestrator.requirementManifests().manifests(),
            orchestrator.hostDeclarationSurface(), Map.of(), BuiltinErrorDeclaration.synthesized(
                checked.input().modules().get(0).ast().span()),
            List.of(IntrinsicKind.INT_CONVERT, IntrinsicKind.NUMBER_CONVERT), Set.of());
    }

    /**
     * The semantic oracle is a third consumer of the same lowered IR, so
     * it must agree with the emitted artifacts. The differential harness
     * drives the oracle and the two shared artifacts over the fixture's
     * production project: every capture form and the repeated loop
     * catches — a fresh mutable binding per catch execution, so a closure
     * created in an earlier catch keeps that execution's value — are in
     * that one run, and either consumer reusing a catch cell fails the
     * comparison.
     */
    private static void testOracleParity(Path root) throws Exception {
        System.out.println("-- the semantic oracle and the shared artifacts "
            + "agree over the fixture's catch bindings --");
        Project project = writeProject(root, fixtureSource(SYNC_FIXTURE));
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "oracle parity");
        SemanticLowerer.ProjectLoweringResult lowered =
            productionLowering(orchestrator);
        checkEq(List.of(), lowered.diagnostics(), "oracle parity: the production "
            + "lowering reports zero diagnostics");
        if (lowered.project() == null) {
            return;
        }
        Path workspace = root.resolve("differential");
        Files.createDirectories(workspace);
        SemanticDifferentialHarness.Verdict verdict =
            SemanticDifferentialHarness.runProject(lowered.project(),
                lowered.tables(), lowered.registries(),
                SemanticDifferentialHarness.Expectation.success(
                    "the catch-binding fixture", List.of("OK"), "null"),
                workspace);
        checkEq(3, verdict.runs().size(), "oracle parity: the differential ran the "
            + "oracle and both shared artifacts: " + verdict.failures());
        check(verdict.pass(), "oracle parity: the three consumers agree: "
            + verdict.failures());
        for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
            check(run.terminal() instanceof SemanticRuntimeModel.Terminal.Success,
                "oracle parity: " + run.consumer() + " completes successfully: "
                    + run.terminal());
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Catch Binding Production Test "
            + "(ISSUE-0882) ===\n");
        ExpectedExecution syncExpected = expectedExecution(SYNC_FIXTURE);
        expectedExecution(ASYNC_FIXTURE);
        Path root = Files.createTempDirectory("deal-catch-binding-");
        try {
            testSyncLuaJit(root.resolve("sync-luajit"), syncExpected);
            testSyncJvm(root.resolve("sync-jvm"), syncExpected);
            testSyncJs(root.resolve("sync-js"), syncExpected);
            testStoreOnlyDirectAssignment(root.resolve("store-only"));
            testOracleParity(root.resolve("oracle-parity"));
            testAsyncLuaJit(root.resolve("async-luajit"));
            testAsyncJvm(root.resolve("async-jvm"));
            testAsyncJs(root.resolve("async-js"));
            testOutOfCatchReference(root.resolve("out-of-catch"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Catch Binding Production Test Passed ===");
    }

    private CatchBindingProductionTest() {
        // Static suite; no instances.
    }
}
