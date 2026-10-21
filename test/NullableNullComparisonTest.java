package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The nullable-null comparison regression (ISSUE-0870): a false-valued
 * nullable operand is a present value, never a null. The exercised
 * published artifacts of all three backends run the corpus fixture's own
 * exact oracle — false, true, and null in both operand orders with
 * {@code ===} and {@code !==}, direct and narrowing positions, beside the
 * optional-field absence/present-null/present-false controls. The
 * LuaJIT seam additionally pins the operand-selection form: the named
 * side is chosen by an explicit branch (Lua's {@code and}/{@code or}
 * idiom would select the other operand when the named one is false), and
 * the null/missing equivalence stays the closed row.
 */
public final class NullableNullComparisonTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The fixture and its structured sidecar (the pinned three-backend oracle). */
    private static final String FIXTURE = "types/nullable-null-comparisons";

    private static final String ENTRY_MODULE = "main";

    /** The fixture's deterministic completion marker (proof the entry ran). */
    private static final String EXPECTED_STDOUT = "OK\n";

    /**
     * The defect's exact idiom: the selected operand falls through to the
     * other side whenever it is false.
     */
    private static final String LOSSY_SELECTION = "(side == \"LEFT\") and l or r";

    /** The fixed operand-selection form of the emitted {@code __cmp}. */
    private static final String EXPLICIT_SELECTION =
        "if side ~= \"LEFT\" then named = r end";

    /** One emitted nullable-null comparison call: selector and named side. */
    private static final Pattern NAMED_CALL = Pattern.compile(
        "__cmp\\(\"(NULLABLE_NULL_EQ|NULLABLE_NULL_NE)\",[^\\n]*?\"(LEFT|RIGHT)\"\\)");

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
    // The fixture, the sidecar, and the production invocation
    // =========================================================================

    private record Project(Path root, Path src, Path entry, Path out) {
    }

    private static String fixtureSource() throws Exception {
        String corpusPath = FIXTURE + ".deal";
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(corpusPath), StandardCharsets.UTF_8),
            corpusPath);
    }

    private static SidecarExpectations.StructuredExpectationSidecar sidecar()
            throws Exception {
        Path file = CORPUS.resolve(FIXTURE + ".expect.json");
        check(Files.isRegularFile(file), "the fixture carries its structured "
            + "expectation sidecar: " + file);
        return SidecarExpectations.StructuredExpectationSidecar.parse(
            Files.readString(file, StandardCharsets.UTF_8));
    }

    private record ExpectedExecution(byte[] stdout, byte[] stderr, int exitCode) {
    }

    private static Map<String, ExpectedExecution> expectedByBackend(
            SidecarExpectations.StructuredExpectationSidecar sidecar) {
        Map<String, ExpectedExecution> expected = new LinkedHashMap<>();
        for (String backend : List.of("luajit", "jvm", "js")) {
            SidecarExpectations.RuntimeExpectation expectation =
                sidecar.expectationFor(backend);
            check(expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed,
                "the sidecar pins the " + backend + " lane");
            if (expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed executed) {
                checkEq("runtime-ok", executed.mode(),
                    "the sidecar's " + backend + " mode");
                expected.put(backend, new ExpectedExecution(executed.stdout(),
                    executed.stderr(), executed.exitCode()));
            }
        }
        for (Map.Entry<String, ExpectedExecution> entry : expected.entrySet()) {
            ExpectedExecution pin = entry.getValue();
            checkEq(EXPECTED_STDOUT, new String(pin.stdout(), StandardCharsets.UTF_8),
                "the sidecar pins the " + entry.getKey()
                    + " fixture's completion marker");
            checkEq("", new String(pin.stderr(), StandardCharsets.UTF_8),
                "the sidecar pins an empty " + entry.getKey() + " stderr");
            checkEq(0, pin.exitCode(), "the sidecar pins the " + entry.getKey()
                + " zero exit code");
        }
        return expected;
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, String source) throws Exception {
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", source);
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
    // The LuaJIT seam and artifact
    // =========================================================================

    private static Set<String> namedCalls(String chunk) {
        Set<String> calls = new LinkedHashSet<>();
        Matcher matcher = NAMED_CALL.matcher(chunk);
        while (matcher.find()) {
            calls.add(matcher.group(1) + ":" + matcher.group(2));
        }
        return calls;
    }

    private static void testLuaJit(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the emitted LuaJIT chunk and its real luajit run --");
        Project project = writeProject(root, fixtureSource());
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit");

        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), "luajit: the production compile "
            + "stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String chunk = Files.readString(artifact, StandardCharsets.UTF_8);
        check(!chunk.contains(LOSSY_SELECTION), "luajit: the emitted __cmp no "
            + "longer selects the named operand with the and/or idiom");
        check(chunk.contains(EXPLICIT_SELECTION), "luajit: the emitted __cmp "
            + "selects the named operand with an explicit branch");
        check(chunk.contains("return (named == nil or named == __MISSING)"),
            "luajit: the equality row keeps the null/missing equivalence");
        check(chunk.contains("return not (named == nil or named == __MISSING)"),
            "luajit: the inequality row keeps the null/missing equivalence");
        Set<String> calls = namedCalls(chunk);
        for (String row : List.of("NULLABLE_NULL_EQ:LEFT",
                "NULLABLE_NULL_EQ:RIGHT", "NULLABLE_NULL_NE:LEFT",
                "NULLABLE_NULL_NE:RIGHT")) {
            check(calls.contains(row), "luajit: the fixture exercises the "
                + row + " comparison row: " + calls);
        }

        assertExecution("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    // =========================================================================
    // The JVM artifact
    // =========================================================================

    private static void testJvm(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the published JVM artifact under javac and java --");
        Project project = writeProject(root, fixtureSource());
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JVM);
        assertProductionEmission(orchestrator, "jvm");

        String className = JvmBackend.classNameFor(ENTRY_MODULE);
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), "jvm: the production compile stages "
            + "the entry class " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        Path classes = project.root().resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toAbsolutePath().toString()), project.out());
        checkEq(0, javac.exitCode(), "jvm: the emitted artifact compiles with "
            + "javac --release 25 -proc:none: " + javac.stdout() + javac.stderr());
        if (javac.exitCode() != 0) {
            return;
        }
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            classes + File.pathSeparator + classpath, className), project.out()),
            expected);
    }

    // =========================================================================
    // The JS artifact
    // =========================================================================

    private static void testJs(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the published JS artifact under node --");
        Project project = writeProject(root, fixtureSource());
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JS);
        assertProductionEmission(orchestrator, "js");
        check(Files.isRegularFile(project.out().resolve(ENTRY_MODULE + ".js")),
            "js: the production compile stages the entry artifact "
                + project.out().resolve(ENTRY_MODULE + ".js"));
        check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
            "js: the production compile deploys the runtime");

        assertExecution("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Nullable-Null Comparison Production Test "
            + "(ISSUE-0870) ===\n");
        Map<String, ExpectedExecution> expected =
            expectedByBackend(sidecar());
        Path root = Files.createTempDirectory("deal-nullable-null-");
        try {
            if (expected.get("luajit") != null) {
                testLuaJit(root.resolve("luajit"), expected.get("luajit"));
            }
            if (expected.get("jvm") != null) {
                testJvm(root.resolve("jvm"), expected.get("jvm"));
            }
            if (expected.get("js") != null) {
                testJs(root.resolve("js"), expected.get("js"));
            }
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Nullable-Null Comparison Production Test Passed ===");
    }
}
