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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The optional-null field read production drive (ISSUE-0883): the corpus
 * fixtures whose optional fields are declared {@code null} — the class case
 * (same-module, imported-class and imported jsonable reads, parameters,
 * returns, presence, write and delete, beside the retained optional
 * {@code T} and optional nullable {@code T} wraps) and the jsonable case
 * (the generated {@code $fromJson}/{@code $toJson} path in the missing and
 * explicit-null states) — through the release-owned production compile of
 * all three backends and the recorded run of every published artifact.
 *
 * <p>The drive reproduces the reported defect before the correction: the
 * validator's read-result descriptor of an optional {@code null} field is
 * the {@code null} atom (the spec's {@code T | null} with {@code T = null}
 * canonicalizes to {@code null}), never the forbidden {@code ?null} wrapper
 * whose construction aborts the compile with an internal error. Each
 * artifact must complete its own entry body and print the pinned
 * {@code OK} marker, so a backend that lost the read, the presence state or
 * the retained wrap fails the exact transcript instead of passing
 * silently.</p>
 */
public final class OptionalNullFieldReadProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /**
     * The driven fixtures: the corpus-relative path plus the relative
     * companion modules the fixture imports (materialized at the entry
     * directory's layout, classification headers stripped).
     */
    private record Fixture(String corpusPath, List<String> companions) {
    }

    private static final List<Fixture> FIXTURES = List.of(
        new Fixture("classes/optional-null-field-reads",
            List.of("classes/optional_null_field_lib")),
        new Fixture("jsonable/optional-null-field-json", List.of()));

    private static final String ENTRY_MODULE = "main";

    /** The fixture's deterministic completion marker (proof the entry ran). */
    private static final String EXPECTED_STDOUT = "OK\n";

    /** The forbidden nullable wrap of the null atom, in every emitted spelling. */
    private static final List<String> FORBIDDEN_WRAPS = List.of(
        "\"?null\"", "\"nullable(null)\"", "\"null|null\"");

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

    private record Project(Path root, Path src, Path entry, Path out) {
    }

    private static String corpusSource(String corpusPath) throws Exception {
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(corpusPath + ".deal"),
                StandardCharsets.UTF_8), corpusPath + ".deal");
    }

    private record ExpectedExecution(byte[] stdout, byte[] stderr, int exitCode) {
    }

    /**
     * The pinned execution of one fixture: its own sidecar is the oracle,
     * so the jsonable fixture pins its byte-exact transcript too.
     */
    private static Map<String, ExpectedExecution> expectedByBackend(Fixture fixture)
            throws Exception {
        Path file = CORPUS.resolve(fixture.corpusPath() + ".expect.json");
        check(Files.isRegularFile(file), fixture.corpusPath()
            + ": the fixture carries its structured expectation sidecar: " + file);
        SidecarExpectations.StructuredExpectationSidecar sidecar =
            SidecarExpectations.StructuredExpectationSidecar.parse(
                Files.readString(file, StandardCharsets.UTF_8));
        Map<String, ExpectedExecution> expected = new LinkedHashMap<>();
        for (String backend : List.of("luajit", "jvm", "js")) {
            SidecarExpectations.RuntimeExpectation expectation =
                sidecar.expectationFor(backend);
            check(expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed,
                fixture.corpusPath() + ": the sidecar pins the " + backend
                    + " lane");
            if (expectation instanceof
                    SidecarExpectations.RuntimeExpectation.Executed executed) {
                checkEq("runtime-ok", executed.mode(), fixture.corpusPath()
                    + ": the sidecar's " + backend + " mode");
                expected.put(backend, new ExpectedExecution(executed.stdout(),
                    executed.stderr(), executed.exitCode()));
            }
        }
        for (Map.Entry<String, ExpectedExecution> entry : expected.entrySet()) {
            ExpectedExecution pin = entry.getValue();
            checkEq(EXPECTED_STDOUT, new String(pin.stdout(),
                    StandardCharsets.UTF_8),
                fixture.corpusPath() + ": the sidecar pins the " + entry.getKey()
                    + " completion marker");
            checkEq("", new String(pin.stderr(), StandardCharsets.UTF_8),
                fixture.corpusPath() + ": the sidecar pins an empty "
                    + entry.getKey() + " stderr");
            checkEq(0, pin.exitCode(), fixture.corpusPath()
                + ": the sidecar pins the " + entry.getKey() + " zero exit code");
        }
        return expected;
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, Fixture fixture) throws Exception {
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", corpusSource(fixture.corpusPath()));
        for (String companion : fixture.companions()) {
            String fileName = companion.substring(companion.lastIndexOf('/') + 1);
            writeFile(root, "src/" + fileName + ".deal", corpusSource(companion));
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
            CompilationOrchestrator orchestrator, String label, String backend) {
        check(orchestrator.diagnostics().isEmpty(), label + " " + backend
            + ": the production compile reports zero diagnostics: "
            + orchestrator.diagnostics());
        checkEq(1, orchestrator.semanticEmissionCount(), label + " " + backend
            + ": the production arm emits exactly one project artifact");
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

    private static void assertExecution(String label, String backend,
            ProcessOutcome run, ExpectedExecution expected) {
        check(!run.stdoutTruncated() && !run.stderrTruncated(),
            label + " " + backend + ": the artifact's streams are complete");
        checkEq(expected.exitCode(), run.exitCode(), label + " " + backend
            + ": the artifact exit code: " + run.stdout() + run.stderr());
        checkEq(new String(expected.stdout(), StandardCharsets.UTF_8),
            run.stdout(), label + " " + backend + ": the artifact stdout");
        checkEq(new String(expected.stderr(), StandardCharsets.UTF_8),
            run.stderr(), label + " " + backend + ": the artifact stderr");
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
    // The emitted descriptor texts
    // =========================================================================

    /**
     * The emitted read-result descriptor text is the {@code null} atom (the
     * spec's {@code T | null} with {@code T = null} canonicalizes to
     * {@code null}): the artifact carries the backend's null-descriptor
     * boundary check and never a nullable spelling of the null atom.
     */
    private static void assertNullAtomReadResult(String label, String backend,
            String nullAtomCheck, String artifactText) {
        check(artifactText.contains(nullAtomCheck), label + " " + backend
            + ": the emitted artifact checks the optional null read against the"
            + " null atom (" + nullAtomCheck + ")");
        for (String forbidden : FORBIDDEN_WRAPS) {
            check(!artifactText.contains(forbidden), label + " " + backend
                + ": the emitted artifact carries no " + forbidden
                + " nullable wrap of the null atom");
        }
    }

    // =========================================================================
    // The LuaJIT artifact
    // =========================================================================

    private static void testLuaJit(Path root, Fixture fixture,
            ExpectedExecution expected) throws Exception {
        String label = fixture.corpusPath();
        System.out.println("-- " + label + ": the published LuaJIT chunk under "
            + "real luajit --");
        Project project = writeProject(root, fixture);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, label, "luajit");

        Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
        check(Files.isRegularFile(artifact), label + " luajit: the production "
            + "compile stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        check(Files.isRegularFile(project.out().resolve("deal/runtime.lua")),
            label + " luajit: the production compile deploys the runtime");
        assertNullAtomReadResult(label, "luajit",
            "pcall(__bcheck, \"null\", \"null\"",
            Files.readString(artifact, StandardCharsets.UTF_8));

        assertExecution(label, "luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    // =========================================================================
    // The JVM artifact
    // =========================================================================

    private static void testJvm(Path root, Fixture fixture,
            ExpectedExecution expected) throws Exception {
        String label = fixture.corpusPath();
        System.out.println("-- " + label + ": the published JVM artifact under "
            + "javac and java --");
        Project project = writeProject(root, fixture);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JVM);
        assertProductionEmission(orchestrator, label, "jvm");

        String className = JvmBackend.classNameFor(ENTRY_MODULE);
        Path artifact = project.out().resolve(className + ".java");
        check(Files.isRegularFile(artifact), label + " jvm: the production "
            + "compile stages the entry class " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertNullAtomReadResult(label, "jvm",
            "JvmRuntime.bcheck(\"null\", \"null\"",
            Files.readString(artifact, StandardCharsets.UTF_8));
        Path classes = project.root().resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            artifact.toAbsolutePath().toString()), project.out());
        checkEq(0, javac.exitCode(), label + " jvm: the emitted artifact "
            + "compiles with javac --release 25 -proc:none: " + javac.stdout()
            + javac.stderr());
        if (javac.exitCode() != 0) {
            return;
        }
        assertExecution(label, "jvm", runProcess(List.of("java", "-cp",
            classes + File.pathSeparator + classpath, className), project.out()),
            expected);
    }

    // =========================================================================
    // The JS artifact
    // =========================================================================

    private static void testJs(Path root, Fixture fixture,
            ExpectedExecution expected) throws Exception {
        String label = fixture.corpusPath();
        System.out.println("-- " + label + ": the published JS artifact under node --");
        Project project = writeProject(root, fixture);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JS);
        assertProductionEmission(orchestrator, label, "js");
        Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
        check(Files.isRegularFile(artifact), label + " js: the production "
            + "compile stages the entry artifact " + artifact);
        check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
            label + " js: the production compile deploys the runtime");
        if (Files.isRegularFile(artifact)) {
            assertNullAtomReadResult(label, "js", "$rt.checkType(\"null\"",
                Files.readString(artifact, StandardCharsets.UTF_8));
        }

        assertExecution(label, "js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Optional-Null Field Read Production Test "
            + "(ISSUE-0883) ===\n");
        Path root = Files.createTempDirectory("deal-optional-null-");
        try {
            for (Fixture fixture : FIXTURES) {
                Map<String, ExpectedExecution> expected = expectedByBackend(fixture);
                String slug = fixture.corpusPath().replace('/', '-');
                if (expected.get("luajit") != null) {
                    testLuaJit(root.resolve(slug + "-luajit"), fixture,
                        expected.get("luajit"));
                }
                if (expected.get("jvm") != null) {
                    testJvm(root.resolve(slug + "-jvm"), fixture,
                        expected.get("jvm"));
                }
                if (expected.get("js") != null) {
                    testJs(root.resolve(slug + "-js"), fixture,
                        expected.get("js"));
                }
            }
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Optional-Null Field Read Production Test Passed ===");
    }
}
