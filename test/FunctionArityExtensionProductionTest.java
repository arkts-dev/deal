package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmNames;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.module.CompilationOrchestrator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The arity-extension position regression (ISSUE-0877): a compatible
 * shorter function value is adapted at every statically typed
 * function-value position, so the position's own boundary validates the
 * adapted value's exact target signature instead of rejecting a
 * source-compatible assignment (docs/spec-v1.3.md Function type
 * compatibility; Arity extension adapter).
 *
 * <p>The three published artifacts run the corpus fixtures' own exact
 * oracles on the real lanes: the sync position corpus under
 * {@code luajit}/{@code java}/{@code node}, the async position corpus
 * through the production async-export invokers on LuaJIT and the JVM and
 * through the artifact's own export surface under {@code node}. The
 * rejection controls pin the exact failure tuples — a table-produced
 * (dynamic) function value whose carried signature is shorter than a
 * declared parameter keeps the exact typed-boundary check, with the
 * E8010 code, message, expected/actual signature texts and the
 * declared-parameter origin identical on every lane, and the sync
 * terminal's own transcript on each lane.</p>
 */
public final class FunctionArityExtensionProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime", "functions");

    private static final String ENTRY_MODULE = "main";

    /** The sync position corpus: all assertions inside the entry export. */
    private static final String POSITIONS =
        "arity-extension-positions";

    /** The async position corpus: a zero-arity async assertion export. */
    private static final String ASYNC_POSITIONS =
        "arity-extension-async-positions";

    /** The dynamic-source rejection control (the sync terminal). */
    private static final String DYNAMIC_SOURCE =
        "arity-extension-dynamic-source-e8010";

    /**
     * The dynamic-source rejection control through the async-export lane
     * (the exact failure tuple on LuaJIT and the JVM).
     */
    private static final String DYNAMIC_SOURCE_ASYNC =
        "arity-extension-dynamic-source-async-e8010";

    /**
     * The no-double-adaptation control: a second arity-extension position
     * over an already-adapted binding keeps the position's exact-signature
     * cell instead of stacking adapters.
     */
    private static final String NO_RESTACK =
        "arity-extension-adapter-value-no-restack-e8010";

    /** The completion the async position corpus proves. */
    private static final String ASYNC_COMPLETION = "320";

    /** The rejection control's exact tuple. */
    private static final String E8010_MESSAGE =
        "function signature mismatch: expected (int,int)->int, got (int)->int";
    private static final String E8010_EXPECTED = "(int,int)->int";
    private static final String E8010_ACTUAL = "(int)->int";

    /** The no-double-adaptation control's exact tuple. */
    private static final String NO_RESTACK_MESSAGE =
        "function signature mismatch: expected (int,int,int)->int, got "
            + "(int,int)->int";

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
    // The corpus fixtures and their pinned lanes
    // =========================================================================

    private static String fixtureSource(String fixture) throws Exception {
        String corpusPath = "backend-runtime/functions/" + fixture + ".deal";
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(fixture + ".deal"),
                StandardCharsets.UTF_8), corpusPath);
    }

    /**
     * The fixture's classified support modules (the corpus companion
     * convention: a sibling {@code <fixture>-<name>.deal}), each with its
     * classification headers stripped.
     */
    private static Map<String, String> companionSources(String fixture)
            throws Exception {
        Map<String, String> companions = new LinkedHashMap<>();
        try (Stream<Path> siblings = Files.list(CORPUS)) {
            for (Path sibling : siblings.sorted().toList()) {
                String name = sibling.getFileName().toString();
                if (!name.endsWith(".deal") || !name.startsWith(fixture + "-")) {
                    continue;
                }
                companions.put(name,
                    ConformanceHarnessMetadata.productionCorpusSource(
                        Files.readString(sibling, StandardCharsets.UTF_8),
                        "backend-runtime/functions/" + name));
            }
        }
        return companions;
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

    private static Map<String, ExpectedExecution> expectedByBackend(String fixture)
            throws Exception {
        SidecarExpectations.StructuredExpectationSidecar pinned = sidecar(fixture);
        Map<String, ExpectedExecution> expected = new LinkedHashMap<>();
        for (String backend : List.of("luajit", "jvm", "js")) {
            SidecarExpectations.RuntimeExpectation expectation =
                pinned.expectationFor(backend);
            check(expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed,
                fixture + ": the sidecar pins the " + backend + " lane");
            if (expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed executed) {
                expected.put(backend, new ExpectedExecution(executed.stdout(),
                    executed.stderr(), executed.exitCode()));
            }
        }
        return expected;
    }

    // =========================================================================
    // The production invocation
    // =========================================================================

    private record Project(Path root, Path src, Path entry, Path out) {
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, String fixture) throws Exception {
        writeFile(root, "deal.json",
            "{\"languageVersion\":\"1.3\",\"moduleRoots\":[\"src\"]}");
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", fixtureSource(fixture));
        for (Map.Entry<String, String> companion : companionSources(fixture)
                .entrySet()) {
            writeFile(root, "src/" + companion.getKey(), companion.getValue());
        }
        Path src = root.resolve("src");
        return new Project(root, src, src.resolve(ENTRY_MODULE + ".deal"),
            root.resolve("out"));
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
        if (command.get(0).equals("javac")) {
            JvmCompilation.Result compiled =
                JvmCompilation.compile(command, workDir);
            return new ProcessOutcome(compiled.exitCode(), compiled.stdout(),
                compiled.stderr(), false, false);
        }
        BoundedProcess.BoundedResult run =
            BoundedProcess.runCaptured(command, workDir);
        return new ProcessOutcome(run.exitCode(), run.stdout(), run.stderr(),
            run.stdoutTruncated(), run.stderrTruncated());
    }

    private static void assertExecution(String backend, ProcessOutcome run,
            ExpectedExecution expected) {
        check(!run.stdoutTruncated() && !run.stderrTruncated(),
            backend + ": the artifact's streams are complete");
        checkEq(expected.exitCode(), run.exitCode(), backend + ": the artifact "
            + "exit code: " + run.stdout() + run.stderr());
        checkEq(new String(expected.stdout(), StandardCharsets.UTF_8),
            run.stdout(), backend + ": the artifact stdout");
        checkEq(new String(expected.stderr(), StandardCharsets.UTF_8),
            run.stderr(), backend + ": the artifact stderr");
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
    // The LuaJIT lane
    // =========================================================================

    private static void testLuaJitPositions(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the LuaJIT position corpus and its real luajit run --");
        Project project = writeProject(root, POSITIONS);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit:" + POSITIONS);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the production compile "
            + "stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
        check(chunk.contains("\"FUNCTION_ADAPT\""), "luajit: the emitted chunk "
            + "carries the arity-extension adapters");
        check(chunk.contains("__adaptInvoke"), "luajit: the emitted adapters run "
            + "the D15 invocation protocol");
        assertExecution("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    private static void testLuaJitAsyncPositions(Path root) throws Exception {
        System.out.println("-- the LuaJIT async position corpus through the "
            + "production async-export invoker --");
        Project project = writeProject(root, ASYNC_POSITIONS);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit:" + ASYNC_POSITIONS);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the async corpus stages "
            + "the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertAsyncCompletion("luajit", new LuaJitAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(artifact,
                "test_arity_extension_async", "int")));
    }

    private static void assertAsyncCompletion(String lane,
            LuaJitAsyncExportInvoker.Result result) {
        check(result instanceof LuaJitAsyncExportInvoker.Result.Value,
            lane + ": the adapted async positions complete a value: " + result);
        if (result instanceof LuaJitAsyncExportInvoker.Result.Value value) {
            checkEq(ASYNC_COMPLETION, value.valueJson(), lane
                + ": the adapted async completion (one-to-two 101, zero-to-one 7, "
                + "awaited 102, sync 4 and the ignored-argument 106)");
        }
    }

    private static void testLuaJitDynamicSource(Path root) throws Exception {
        System.out.println("-- the LuaJIT dynamic-source control's own terminal --");
        Project project = writeProject(root, DYNAMIC_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + DYNAMIC_SOURCE);
        ProcessOutcome run = runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out());
        checkEq(1, run.exitCode(), "luajit: the dynamic-source control exits one: "
            + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "luajit: the "
            + "dynamic-source control's own terminal transcript");
        checkEq("", run.stderr(), "luajit: the dynamic-source control's stderr");
    }

    private static void testLuaJitDynamicSourceAsync(Path root) throws Exception {
        System.out.println("-- the LuaJIT dynamic-source failure tuple through the "
            + "async-export lane --");
        Project project = writeProject(root, DYNAMIC_SOURCE_ASYNC);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + DYNAMIC_SOURCE_ASYNC);
        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        LuaJitAsyncExportInvoker.Result result = new LuaJitAsyncExportInvoker()
            .invoke(new AsyncExportInvocationRequest(artifact,
                "test_arity_extension_dynamic_source_async_e8010", "int"));
        assertDealError("luajit", result instanceof LuaJitAsyncExportInvoker.Result.DealError
                ? (LuaJitAsyncExportInvoker.Result.DealError) result : null,
            project.entry().toAbsolutePath().toString(),
            "the adapted async positions' dynamic-source control");
    }

    // =========================================================================
    // The JVM lane
    // =========================================================================

    private record JvmArtifacts(Project project, Path classes, String className) {
    }

    private static JvmArtifacts compileJvm(Path root, String fixture)
            throws Exception {
        Project project = writeProject(root, fixture);
        assertProductionEmission(productionCompile(project, Backend.JVM),
            "jvm:" + fixture);
        String className = JvmNames.classNameFor(ENTRY_MODULE);
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), "jvm: the production compile stages "
            + "the entry class " + artifact);
        Path classes = project.root().resolve("classes");
        Files.createDirectories(classes);
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", absoluteClasspath(), "-d", classes.toString(),
            artifact.toAbsolutePath().toString()), project.out());
        checkEq(0, javac.exitCode(), "jvm: the emitted artifact compiles with "
            + "javac --release 25 -proc:none: " + javac.stdout() + javac.stderr());
        return new JvmArtifacts(project, classes, className);
    }

    private static void testJvmPositions(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the JVM position corpus under javac and java --");
        JvmArtifacts artifacts = compileJvm(root, POSITIONS);
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out()), expected);
    }

    private static void testJvmAsyncPositions(Path root) throws Exception {
        System.out.println("-- the JVM async position corpus through the "
            + "production async-export invoker --");
        JvmArtifacts artifacts = compileJvm(root, ASYNC_POSITIONS);
        Path entryClass = artifacts.classes().resolve(
            artifacts.className() + ".class");
        JvmAsyncExportInvoker.Result result = new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryClass,
                "test_arity_extension_async", "int"));
        check(result instanceof JvmAsyncExportInvoker.Result.Value,
            "jvm: the adapted async positions complete a value: " + result);
        if (result instanceof JvmAsyncExportInvoker.Result.Value value) {
            checkEq(ASYNC_COMPLETION, value.valueJson(), "jvm: the adapted async "
                + "completion (one-to-two 101, zero-to-one 7, awaited 102, sync 4 "
                + "and the ignored-argument 106)");
        }
    }

    private static void testJvmDynamicSource(Path root) throws Exception {
        System.out.println("-- the JVM dynamic-source control's own terminal --");
        JvmArtifacts artifacts = compileJvm(root, DYNAMIC_SOURCE);
        ProcessOutcome run = runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out());
        checkEq(1, run.exitCode(), "jvm: the dynamic-source control exits one: "
            + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "jvm: the "
            + "dynamic-source control's own terminal transcript");
        checkEq("", run.stderr(), "jvm: the dynamic-source control's stderr");
    }

    private static void testJvmDynamicSourceAsync(Path root) throws Exception {
        System.out.println("-- the JVM dynamic-source failure tuple through the "
            + "async-export lane --");
        JvmArtifacts artifacts = compileJvm(root, DYNAMIC_SOURCE_ASYNC);
        Path entryClass = artifacts.classes().resolve(
            artifacts.className() + ".class");
        JvmAsyncExportInvoker.Result result = new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(entryClass,
                "test_arity_extension_dynamic_source_async_e8010", "int"));
        assertDealError("jvm", result instanceof JvmAsyncExportInvoker.Result.DealError
                ? (JvmAsyncExportInvoker.Result.DealError) result : null,
            artifacts.project().entry().toAbsolutePath().toString(),
            "the adapted async positions' dynamic-source control");
    }

    // =========================================================================
    // The JS lane
    // =========================================================================

    private static void testJsPositions(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the JS position corpus under node --");
        Project project = writeProject(root, POSITIONS);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + POSITIONS);
        assertExecution("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    /**
     * The async corpus under {@code node}: the artifact's own export
     * surface is awaited exactly like the shared lane contract does, so
     * the completion value and the fixture's own transcript are observed
     * once.
     */
    private static void testJsAsyncPositions(Path root) throws Exception {
        System.out.println("-- the JS async position corpus under node --");
        Project project = writeProject(root, ASYNC_POSITIONS);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + ASYNC_POSITIONS);
        writeFile(project.out(), "AsyncDriver.js", """
            "use strict";
            const $entry = require("./main.js");
            (async () => {
              const $target = $entry["test_arity_extension_async"];
              const $value = await $target.$f();
              process.stdout.write("VALUE " + $value + "\\n");
            })().catch(($e) => {
              process.stdout.write("ERROR " + ($e && $e.message) + "\\n");
              process.exitCode = 1;
            });
            """);
        ProcessOutcome run = runProcess(List.of("node", "AsyncDriver.js"),
            project.out());
        checkEq(0, run.exitCode(), "js: the async corpus driver exits zero: "
            + run.stdout() + run.stderr());
        checkEq("ASYNC OK\nVALUE " + ASYNC_COMPLETION + "\n", run.stdout(),
            "js: the adapted async completion and the fixture's own transcript");
    }

    private static void testJsDynamicSource(Path root) throws Exception {
        System.out.println("-- the JS dynamic-source control's own terminal --");
        Project project = writeProject(root, DYNAMIC_SOURCE);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + DYNAMIC_SOURCE);
        ProcessOutcome run = runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out());
        checkEq(1, run.exitCode(), "js: the dynamic-source control exits one: "
            + run.stdout() + run.stderr());
        checkEq("", run.stdout(), "js: the dynamic-source control's stdout");
        checkEq("DEAL_ERROR_CODE: E8010 " + E8010_MESSAGE + " at "
                + project.entry().toAbsolutePath() + ":4:22\n", run.stderr(),
            "js: the dynamic-source control's own terminal transcript");
    }

    private static void testJsDynamicSourceAsync(Path root) throws Exception {
        System.out.println("-- the JS dynamic-source failure tuple through the "
            + "artifact's export surface --");
        Project project = writeProject(root, DYNAMIC_SOURCE_ASYNC);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + DYNAMIC_SOURCE_ASYNC);
        writeFile(project.out(), "ErrorDriver.js", """
            "use strict";
            const $entry = require("./main.js");
            (async () => {
              const $target = $entry["test_arity_extension_dynamic_source_async_e8010"];
              try {
                await $target.$f();
                process.stdout.write("NO_ERROR\\n");
                process.exitCode = 1;
              } catch ($e) {
                const $tuple = ["code", "message", "expected", "actual", "file",
                  "line", "column"].map(($k) => $k + "=" + String($e[$k]));
                process.stdout.write("TUPLE " + $tuple.join(" ") + "\\n");
              }
            })();
            """);
        ProcessOutcome run = runProcess(List.of("node", "ErrorDriver.js"),
            project.out());
        checkEq(0, run.exitCode(), "js: the error driver observes the tuple: "
            + run.stdout() + run.stderr());
        checkEq("TUPLE code=E8010 message=" + E8010_MESSAGE + " expected="
                + E8010_EXPECTED + " actual=" + E8010_ACTUAL + " file="
                + project.entry().toAbsolutePath() + " line=4 column=22\n",
            run.stdout(), "js: the dynamic-source control's exact failure tuple");
    }

    private static void testLuaJitNoRestack(Path root) throws Exception {
        System.out.println("-- the LuaJIT no-double-adaptation control --");
        Project project = writeProject(root, NO_RESTACK);
        assertProductionEmission(productionCompile(project, Backend.LUAJIT),
            "luajit:" + NO_RESTACK);
        ProcessOutcome run = runProcess(
            List.of("luajit", ENTRY_MODULE + ".lua"), project.out());
        checkEq(1, run.exitCode(), "luajit: the no-restack control exits one: "
            + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "luajit: the "
            + "no-restack control's own terminal transcript");
        checkEq("", run.stderr(), "luajit: the no-restack control's stderr");
    }

    private static void testJvmNoRestack(Path root) throws Exception {
        System.out.println("-- the JVM no-double-adaptation control --");
        JvmArtifacts artifacts = compileJvm(root, NO_RESTACK);
        ProcessOutcome run = runProcess(List.of("java", "-cp",
            artifacts.classes() + File.pathSeparator + absoluteClasspath(),
            artifacts.className()), artifacts.project().out());
        checkEq(1, run.exitCode(), "jvm: the no-restack control exits one: "
            + run.stdout() + run.stderr());
        checkEq("DEAL_ERROR_CODE: E8010\n", run.stdout(), "jvm: the no-restack "
            + "control's own terminal transcript");
        checkEq("", run.stderr(), "jvm: the no-restack control's stderr");
    }

    private static void testJsNoRestack(Path root) throws Exception {
        System.out.println("-- the JS no-double-adaptation control --");
        Project project = writeProject(root, NO_RESTACK);
        assertProductionEmission(productionCompile(project, Backend.JS), "js:"
            + NO_RESTACK);
        ProcessOutcome run = runProcess(List.of("node", ENTRY_MODULE + ".js"),
            project.out());
        checkEq(1, run.exitCode(), "js: the no-restack control exits one: "
            + run.stdout() + run.stderr());
        checkEq("", run.stdout(), "js: the no-restack control's stdout");
        checkEq("DEAL_ERROR_CODE: E8010 function signature mismatch: expected "
                + "(int,int,int)->int, got (int,int)->int at "
                + project.entry().toAbsolutePath() + ":4:24\n", run.stderr(),
            "js: the no-restack control's own terminal transcript");
    }

    // =========================================================================
    // The shared failure tuple
    // =========================================================================

    private static void assertDealError(String lane,
            LuaJitAsyncExportInvoker.Result.DealError error, String expectedFile,
            String what) {
        check(error != null, lane + ": " + what + " raises the pinned DEAL error");
        if (error == null) {
            return;
        }
        checkEq("E8010", error.code(), lane + ": the failure code");
        checkEq(E8010_MESSAGE, error.message(), lane + ": the failure message");
        checkEq(E8010_EXPECTED, error.expected(), lane + ": the expected token");
        checkEq(E8010_ACTUAL, error.actual(), lane + ": the actual token");
        checkEq(expectedFile, error.file(), lane + ": the failure's source file");
        checkEq(4, error.line(), lane + ": the declared parameter's line");
        checkEq(22, error.column(), lane + ": the declared parameter's column");
    }

    private static void assertDealError(String lane,
            JvmAsyncExportInvoker.Result.DealError error, String expectedFile,
            String what) {
        check(error != null, lane + ": " + what + " raises the pinned DEAL error");
        if (error == null) {
            return;
        }
        checkEq("E8010", error.code(), lane + ": the failure code");
        checkEq(E8010_MESSAGE, error.message(), lane + ": the failure message");
        checkEq(E8010_EXPECTED, error.expected(), lane + ": the expected token");
        checkEq(E8010_ACTUAL, error.actual(), lane + ": the actual token");
        checkEq(expectedFile, error.file(), lane + ": the failure's source file");
        checkEq(4, error.line(), lane + ": the declared parameter's line");
        checkEq(22, error.column(), lane + ": the declared parameter's column");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Function Arity Extension Position Production Test "
            + "(ISSUE-0877) ===\n");
        Map<String, ExpectedExecution> positions = expectedByBackend(POSITIONS);
        Map<String, ExpectedExecution> asyncPositions =
            expectedByBackend(ASYNC_POSITIONS);
        Map<String, ExpectedExecution> dynamicSource =
            expectedByBackend(DYNAMIC_SOURCE);
        expectedByBackend(DYNAMIC_SOURCE_ASYNC);
        for (Map<String, ExpectedExecution> expected : List.of(positions,
                asyncPositions, dynamicSource)) {
            for (ExpectedExecution execution : expected.values()) {
                checkEq("", new String(execution.stderr(), StandardCharsets.UTF_8),
                    "every pinned lane carries an empty stderr");
            }
        }
        checkEq("OK\n", new String(positions.get("luajit").stdout(),
            StandardCharsets.UTF_8), "the position corpus pins its marker");
        checkEq(0, positions.get("luajit").exitCode(),
            "the position corpus pins its exit code");
        checkEq("ASYNC OK\n", new String(asyncPositions.get("js").stdout(),
            StandardCharsets.UTF_8), "the async corpus pins its marker");
        checkEq(1, dynamicSource.get("luajit").exitCode(),
            "the dynamic-source control pins its nonzero exit code");
        for (String fixture : List.of(DYNAMIC_SOURCE, DYNAMIC_SOURCE_ASYNC,
                NO_RESTACK)) {
            for (String lane : List.of("luajit", "jvm", "js")) {
                SidecarExpectations.RuntimeExpectation pinned =
                    sidecar(fixture).expectationFor(lane);
                check(pinned instanceof
                        SidecarExpectations.RuntimeExpectation.Executed,
                    fixture + ": the sidecar pins the " + lane + " lane");
                if (!(pinned instanceof
                        SidecarExpectations.RuntimeExpectation.Executed executed)) {
                    continue;
                }
                checkEq("runtime-error", executed.mode(), fixture
                    + ": the sidecar pins the runtime-error mode");
                checkEq(1, executed.exitCode(), fixture + ": the sidecar pins "
                    + "the nonzero exit code");
                checkEq("", new String(executed.stderr(), StandardCharsets.UTF_8),
                    fixture + ": the sidecar pins an empty stderr");
                var error = executed.error();
                check(error != null, fixture + ": the sidecar pins the error");
                if (error == null) {
                    continue;
                }
                checkEq("E8010", error.code(), fixture
                    + ": the sidecar pins the failure code");
                String message = fixture.equals(NO_RESTACK)
                    ? NO_RESTACK_MESSAGE : E8010_MESSAGE;
                String expectedToken = fixture.equals(NO_RESTACK)
                    ? "(int,int,int)->int" : E8010_EXPECTED;
                String actualToken = fixture.equals(NO_RESTACK)
                    ? "(int,int)->int" : E8010_ACTUAL;
                int column = fixture.equals(NO_RESTACK) ? 24 : 22;
                checkEq(message, error.message(), fixture
                    + ": the sidecar pins the failure message");
                checkEq(expectedToken, error.expected().orElse(null), fixture
                    + ": the sidecar pins the expected token");
                checkEq(actualToken, error.actual().orElse(null), fixture
                    + ": the sidecar pins the actual token");
                checkEq(8, error.line(), fixture
                    + ": the sidecar pins the corpus line");
                checkEq(column, error.column(), fixture
                    + ": the sidecar pins the corpus column");
            }
        }

        Path root = Files.createTempDirectory("deal-arity-extension-");
        try {
            testLuaJitPositions(root.resolve("luajit-positions"),
                positions.get("luajit"));
            testLuaJitAsyncPositions(root.resolve("luajit-async"));
            testLuaJitDynamicSource(root.resolve("luajit-dynamic"));
            testLuaJitDynamicSourceAsync(root.resolve("luajit-dynamic-async"));
            testJvmPositions(root.resolve("jvm-positions"), positions.get("jvm"));
            testJvmAsyncPositions(root.resolve("jvm-async"));
            testJvmDynamicSource(root.resolve("jvm-dynamic"));
            testJvmDynamicSourceAsync(root.resolve("jvm-dynamic-async"));
            testJsPositions(root.resolve("js-positions"), positions.get("js"));
            testJsAsyncPositions(root.resolve("js-async"));
            testJsDynamicSource(root.resolve("js-dynamic"));
            testJsDynamicSourceAsync(root.resolve("js-dynamic-async"));
            testLuaJitNoRestack(root.resolve("luajit-no-restack"));
            testJvmNoRestack(root.resolve("jvm-no-restack"));
            testJsNoRestack(root.resolve("js-no-restack"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Function Arity Extension Position Production Test "
            + "Passed ===");
    }
}
