package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
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
 * The catch-variable narrowing isolation regression (ISSUE-0888): the catch
 * identifier declares a fresh {@code Error} binding
 * (docs/spec-v1.4.md §try / catch), so a same-named outer binding's null
 * refinement never types the catch read and never overrides the catch
 * binding's own Error facts, while the catch scope's exit restores the
 * outer refinement the language did not invalidate (§Name resolution —
 * Shadowing, §Nullable semantics — narrowing, §Narrowing across try/catch).
 *
 * <p>The accepted fixture is compiled through the real whole-project
 * pipeline on every declared backend, its three published artifacts are
 * inspected and executed, and its own postconditions must hold: the
 * int-, string- and null-refined outer bindings read their own catch
 * Error value through both a declaration transfer and the error's message
 * field, and each one is usable as its refined type after the catch;
 * nested catches, nested blocks, renamed and unrefined catches, a
 * reassigned catch binding, a try/catch nested inside an enclosing try
 * block that reassigns its own catch binding, and a parameter refinement
 * keep the outer refinement, each with the exact sidecar transcript. The
 * rejected forms — the Error binding transferred to a string or an int
 * target, an outer binding assigned inside the catch, an awaited
 * resumption inside the catch, an awaited resumption inside the try block,
 * and a try-block assignment inside a nested try/catch — are compiled
 * through the same pipeline on every backend and must fail with exactly
 * one E3001 at the pinned identifier range with no published artifact: a
 * checker that let an outer refinement type the catch binding accepts the
 * unsound transfer, and one that resurrected a cleared refinement rejects
 * a valid Error read.</p>
 */
public final class CatchNarrowingIsolationProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The fixture and its structured sidecar (the three-backend oracle). */
    private static final String FIXTURE =
        "error-handling/catch-shadow-narrowed-binding";

    private static final String ENTRY_MODULE = "main";

    private static final List<String> BACKENDS = List.of("luajit", "jvm", "js");

    /** The fixture's deterministic completion marker (proof the entry ran). */
    private static final String EXPECTED_STDOUT = "OK\n";

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

    /**
     * The sidecar's per-backend pins. The accepted fixture is a
     * {@code runtime-ok} case on every declared backend, so the expectation
     * is the exact completion transcript and a zero exit code — a backend a
     * correct Error read was rejected on fails here instead of passing
     * silently.
     */
    private static Map<String, ExpectedExecution> expectedByBackend(
            SidecarExpectations.StructuredExpectationSidecar sidecar) {
        Map<String, ExpectedExecution> expected = new LinkedHashMap<>();
        for (String backend : BACKENDS) {
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
            checkEq(EXPECTED_STDOUT, new String(pin.stdout(),
                StandardCharsets.UTF_8), "the sidecar pins the "
                + entry.getKey() + " fixture's completion marker");
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

    /** The entry artifact the backend publishes for the fixture. */
    private static Path entryArtifact(Project project, Backend backend) {
        return switch (backend) {
            case JS -> project.out().resolve(ENTRY_MODULE + ".js");
            case LUAJIT -> project.out().resolve(ENTRY_MODULE + ".lua");
            case JVM -> project.out().resolve(JvmBackend.classNameFor(ENTRY_MODULE)
                + ".java");
        };
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
    // The accepted fixture in each production lane
    // =========================================================================

    private static void testLuaJit(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the accepted fixture: LuaJIT artifact --");
        Project project = writeProject(root, fixtureSource());
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit");
        Path artifact = entryArtifact(project, Backend.LUAJIT);
        check(Files.isRegularFile(artifact), "luajit: the production compile "
            + "stages the entry chunk " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertExecution("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    private static void testJvm(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the accepted fixture: JVM artifact --");
        Project project = writeProject(root, fixtureSource());
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JVM);
        assertProductionEmission(orchestrator, "jvm");
        Path artifact = entryArtifact(project, Backend.JVM);
        check(Files.isRegularFile(artifact), "jvm: the production compile stages "
            + "the entry class " + artifact);
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        String className = JvmBackend.classNameFor(ENTRY_MODULE);
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

    private static void testJs(Path root, ExpectedExecution expected)
            throws Exception {
        System.out.println("-- the accepted fixture: JS artifact --");
        Project project = writeProject(root, fixtureSource());
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.JS);
        assertProductionEmission(orchestrator, "js");
        Path artifact = entryArtifact(project, Backend.JS);
        check(Files.isRegularFile(artifact), "js: the production compile stages "
            + "the entry artifact " + artifact);
        check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
            "js: the production compile deploys the runtime");
        if (!Files.isRegularFile(artifact)) {
            return;
        }
        assertExecution("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    // =========================================================================
    // The rejected forms: one exact frontend rejection on every backend
    // =========================================================================

    /**
     * One rejected source and its pinned single diagnostic: the source, the
     * exact code, the identifier's line/column, the identifier's scalar
     * length, and the exact message. The message names the two types, so a
     * checker that typed the catch read as the narrowed outer type fails on
     * the message as well as on the acceptance.
     */
    private record Rejection(String name, String source, String code,
                             int line, int column, int length, String message) {
    }

    private static List<Rejection> rejections() {
        return List.of(
            new Rejection("string-refined catch binding transferred to string",
                """
                export function main(): null {
                  let e: string | null = "outer";
                  if (e !== null) {
                    try {
                      throw { code: "C_STR", message: "string refinement" };
                    } catch (e) {
                      let unsound: string = e;
                    }
                  }
                  return null;
                }
                """, "E3001", 7, 29, 1, "Cannot assign Error to string"),
            new Rejection("number-refined catch binding transferred to number",
                """
                export function main(): null {
                  let e: number | null = 1;
                  if (e !== null) {
                    try {
                      throw { code: "C_INT", message: "number refinement" };
                    } catch (e) {
                      let unsound: number = e;
                    }
                  }
                  return null;
                }
                """, "E3001", 7, 29, 1, "Cannot assign Error to number"),
            new Rejection("enclosing binding assigned inside the catch",
                """
                export function main(): null {
                  let value: number | null = 1;
                  if (value !== null) {
                    try {
                      throw { code: "C_SET_NULL", message: "set null" };
                    } catch (err) {
                      value = null;
                    }
                    let unsound: number = value;
                  }
                  return null;
                }
                """, "E3001", 9, 27, 5, "Cannot assign number | null to number"),
            new Rejection("awaited resumption inside the catch",
                """
                async function echoInt(value: number): number {
                  return value;
                }

                async function probe(e: number | null): number {
                  if (e !== null) {
                    try {
                      throw { code: "C_AWAIT", message: "await" };
                    } catch (e) {
                      let captured: Error = e;
                      let resumed: number = await echoInt(1);
                      let afterResume: Error = e;
                    }
                    let unsound: number = e;
                  }
                  return 0;
                }

                export function main(): null {
                  return null;
                }
                """, "E3001", 14, 27, 1, "Cannot assign number | null to number"),
            new Rejection("awaited resumption inside the try block",
                """
                async function echoInt(value: number): number {
                  return value;
                }

                async function probe(e: number | null): number {
                  if (e !== null) {
                    try {
                      let resumed: number = await echoInt(1);
                      throw { code: "C_AWAIT_TRY", message: "await" };
                    } catch (e) {
                      let captured: Error = e;
                    }
                    let unsound: number = e;
                  }
                  return 0;
                }

                export function main(): null {
                  return null;
                }
                """, "E3001", 13, 27, 1, "Cannot assign number | null to number"),
            new Rejection("try-block assignment inside a nested try",
                """
                export function main(): null {
                  let e: number | null = 1;
                  if (e !== null) {
                    try {
                      try {
                        e = null;
                      } catch (err) {
                      }
                      let unsound: number = e;
                    } catch (outer) {
                    }
                  }
                  return null;
                }
                """, "E3001", 9, 29, 1, "Cannot assign number | null to number"));
    }

    private static void testRejections(Path root) throws Exception {
        System.out.println("-- the rejected forms on every declared backend --");
        for (Rejection rejection : rejections()) {
            for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM,
                    Backend.JS)) {
                String lane = backend.name().toLowerCase(java.util.Locale.ROOT);
                String what = rejection.name() + " [" + lane + "]";
                Project project = writeProject(
                    root.resolve(rejection.name().replace(' ', '-') + "-" + lane),
                    rejection.source());
                CompilationOrchestrator orchestrator = productionCompile(project,
                    backend);
                check(orchestrator.semanticEmissionCount() == 0,
                    what + ": a rejected frontend emits no artifact");
                check(!Files.isRegularFile(entryArtifact(project, backend)),
                    what + ": no entry artifact is published on rejection");
                List<CompilerDiagnostic> errors = orchestrator.diagnostics()
                    .stream().filter(d -> "error".equals(d.severity())).toList();
                checkEq(1, errors.size(), what + ": exactly one error: "
                    + orchestrator.diagnostics());
                if (errors.size() != 1) {
                    continue;
                }
                CompilerDiagnostic error = errors.getFirst();
                checkEq(rejection.code(), error.code(), what + ": the diagnostic "
                    + "code");
                checkEq(rejection.line(), error.line(), what + ": the diagnostic "
                    + "line");
                checkEq(rejection.column(), error.column(), what + ": the "
                    + "diagnostic column");
                checkEq(rejection.length(), error.range().scalarLength(), what
                    + ": the diagnostic's identifier range length");
                checkEq(rejection.message(), error.message(), what + ": the "
                    + "diagnostic message");
            }
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Catch Narrowing Isolation Production Test "
            + "(ISSUE-0888) ===\n");
        Map<String, ExpectedExecution> expected =
            expectedByBackend(sidecar());
        Path root = Files.createTempDirectory("deal-catch-narrowing-");
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
            testRejections(root.resolve("rejections"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Catch Narrowing Isolation Production Test Passed ===");
    }
}
