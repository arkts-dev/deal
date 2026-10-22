package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.lua.AsyncExportInvocationRequest;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * ISSUE-0881: the Error class instance a program throws is the identical
 * instance its catch binds — from the FIRST throw on, on every production
 * backend.
 *
 * <p>DEAL's {@code Error} is a builtin nominal class whose values compare by
 * reference identity (docs/spec-v1.3.md, "Error type" and the {@code ===}
 * semantics), so {@code throw}/{@code catch} must transport the thrown
 * instance itself, never a replacement carrier: {@code caught === original},
 * a field write through either alias is visible through the other, and the
 * diagnostic facts the failure needs (the throw site's origin, the active
 * frames, an absent expected/actual/cause, and any linked cause) travel
 * with that one instance instead of materializing a second object.</p>
 *
 * <p>Three regressions run the real production surface:</p>
 * <ul>
 *   <li>the corpus fixture {@code error-handling/error-identity-first-throw}
 *       through the emitted artifacts of all three backends (real
 *       {@code luajit}, {@code java}, {@code node}): its entry terminal
 *       executes the identity, two-alias mutation, rethrow, nested-catch and
 *       parameter/return-crossing postconditions and prints one marker per
 *       group, and the exact transcript is the one the sidecar pins;</li>
 *   <li>the fixture's declared {@code test_awaited_error_identity} export
 *       through the production async-export invokers on LuaJIT and the JVM
 *       and through the published JS artifact under {@code node}, so the
 *       awaited error is the same instance on every lane;</li>
 *   <li>the canonical failure metadata of the identity-preserving paths on
 *       the semantic oracle and both shared artifacts: a mutated first-throw
 *       rethrow keeps the FIRST throw's origin with no invented cause, and a
 *       handler's replacement keeps its own origin and links the caught
 *       failure as its cause, plus the JS artifact's byte-exact escaped
 *       terminal.</li>
 * </ul>
 */
public final class ErrorIdentityProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The fixture and its structured sidecar (the pinned three-backend oracle). */
    private static final String FIXTURE = "error-handling/error-identity-first-throw";

    private static final String ENTRY_MODULE = "main";

    /** The awaiting lane's declared assertion export and completion. */
    private static final String AWAITED_EXPORT = "test_awaited_error_identity";

    private static final String AWAITED_DESCRIPTOR = "string";

    private static final String AWAITED_VALUE_JSON = "\"awaited\"";

    /** The fixture's deterministic per-group markers (proof each group ran). */
    private static final String EXPECTED_STDOUT =
        "OK|first-throw\nOK|rethrow\nOK|nested-catch\nOK|crossing\n";

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    // =========================================================================
    // The fixture, the sidecar, and the metadata fixtures
    // =========================================================================

    /**
     * The mutated-rethrow fixture: the first throw of a constructed Error
     * happens in {@code lib.failer}, its own handler mutates the caught
     * instance's code/message and rethrows it, and the failure escapes. The
     * escaping failure is the first throw's failure with the instance's
     * current fields — its origin stays the FIRST throw's site and no cause
     * is invented.
     */
    private static final String MUTATED_LIB_SOURCE = """
        export function raise(): int {
          let original: Error = { code: "FIRST", message: "first" };
          let alias: Error = original;
          try {
            throw original;
          } catch (caught) {
            if (caught !== original || caught !== alias) {
              throw { code: "TEST_FAIL", message: "the first throw lost the identity" };
            }
            caught.code = "SECOND";
            caught.message = "second";
            throw caught;
          }
          return 0;
        }
        """;

    private static final String MUTATED_APP_SOURCE = """
        import * as lib from "./lib";

        export function main(): null {
          let x: int = lib.raise();
          return null;
        }
        """;

    /**
     * The handler-replacement fixture: {@code inner} raises the typed E8001,
     * the handler raises a constructed replacement Error, and the enclosing
     * failure is the replacement's own failure (its own origin, no
     * expected/actual) with the caught E8001 linked as its cause.
     */
    private static final String WRAPPED_LIB_SOURCE = """
        function inner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export function raise(): int {
          let replacement: Error = { code: "OUTER", message: "outer" };
          try {
            return inner();
          } catch (e) {
            throw replacement;
          }
        }
        """;

    // =========================================================================
    // The production compile
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
                    + " fixture's per-group markers");
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

    private static Project writeProject(Path root, String entrySource)
            throws Exception {
        writeFile(root, "src/main.deal", entrySource);
        Path src = root.resolve("src");
        return new Project(root, src, src.resolve("main.deal"), root.resolve("out"));
    }

    private static Project writeProject(Path root, String libSource,
                                        String appSource) throws Exception {
        writeFile(root, "src/lib.deal", libSource);
        writeFile(root, "src/app.deal", appSource);
        Path src = root.resolve("src");
        return new Project(root, src, src.resolve("app.deal"), root.resolve("out"));
    }

    private static CompilationOrchestrator productionCompile(Project project,
            Backend backend) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            project.entry().toAbsolutePath(), project.out(), false, false, false,
            false, backend, null, List.of(project.src().toAbsolutePath()),
            Path.of("").toAbsolutePath(), null, productionInvocation());
        orchestrator.compile();
        check(orchestrator.diagnostics().isEmpty(), backend + ": the production "
            + "compile of " + project.entry().getFileName() + " reports zero "
            + "diagnostics: " + orchestrator.diagnostics());
        return orchestrator;
    }

    private record ProcessOutcome(int exitCode, String stdout, String stderr) {
        String output() {
            return "exit=" + exitCode + " stdout=<" + stdout + "> stderr=<"
                + stderr + ">";
        }
    }

    private static ProcessOutcome runProcess(List<String> command, Path workDir)
            throws Exception {
        if (command.get(0).equals("javac")) {
            JvmCompilation.Result compiled = JvmCompilation.compile(command, workDir);
            return new ProcessOutcome(compiled.exitCode(), compiled.stdout(),
                compiled.stderr());
        }
        BoundedProcess.BoundedResult run = BoundedProcess.runCaptured(command, workDir);
        return new ProcessOutcome(run.exitCode(), run.stdout(), run.stderr());
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

    private static void assertExecution(String backend, ProcessOutcome run,
            ExpectedExecution expected) {
        checkEq(expected.exitCode(), run.exitCode(), backend + ": the published "
            + "artifact keeps the Error identity and exits with the pinned code: "
            + run.output());
        checkEq(new String(expected.stdout(), StandardCharsets.UTF_8), run.stdout(),
            backend + ": the identity postcondition markers: " + run.output());
        checkEq(new String(expected.stderr(), StandardCharsets.UTF_8), run.stderr(),
            backend + ": the pinned stderr: " + run.output());
    }

    // =========================================================================
    // Regression 1: the identity postconditions on the three emitted artifacts
    // =========================================================================

    private static void testLuaJitIdentityArtifact(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the published LuaJIT chunk: the fixture's entry "
            + "terminal under real luajit --");
        Project project = writeProject(root, fixtureSource());
        productionCompile(project, Backend.LUAJIT);
        check(Files.isRegularFile(project.out().resolve("deal/runtime.lua")),
            "luajit: the production compile deploys the runtime");
        assertExecution("luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), project.out()),
            expected);
    }

    private static void testJvmIdentityArtifact(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the published JVM artifact: the fixture's entry "
            + "terminal under javac and java --");
        Project project = writeProject(root, fixtureSource());
        productionCompile(project, Backend.JVM);
        Path classes = project.root().resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d", classes.toString(),
            project.out().resolve("Main.java").toAbsolutePath().toString()),
            project.out());
        checkEq(0, javac.exitCode(), "jvm: the emitted artifact compiles with "
            + "javac --release 25 -proc:none: " + javac.stdout() + javac.stderr());
        if (javac.exitCode() != 0) {
            return;
        }
        assertExecution("jvm", runProcess(List.of("java", "-cp",
            classes + File.pathSeparator + classpath, "Main"), project.out()),
            expected);
    }

    private static void testJsIdentityArtifact(Path root,
            ExpectedExecution expected) throws Exception {
        System.out.println("-- the published JS artifact: the fixture's entry "
            + "terminal under node --");
        Project project = writeProject(root, fixtureSource());
        productionCompile(project, Backend.JS);
        check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
            "js: the production compile deploys the runtime");
        assertExecution("js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), project.out()),
            expected);
    }

    // =========================================================================
    // Regression 2: the canonical failure metadata of the identity paths
    // =========================================================================

    private record LoweredProject(SemanticLowerer.ProjectLoweringResult result,
                                  Path root) {
    }

    private static LoweredProject lower(Project project) throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            project.entry().toAbsolutePath(), project.out(), false, null,
            List.of(project.src().toAbsolutePath()), null);
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null || manifests.manifests() == null) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            throw new IllegalStateException("the fixture project did not build: "
                + detail + " / " + orchestrator.diagnostics());
        }
        SemanticLowerer.ProjectLoweringResult result = SemanticLowerer.lowerProject(
            productionInvocation(), built.input(), built.index(),
            manifests.manifests(), orchestrator.hostDeclarationSurface(),
            new LinkedHashMap<>(), new LinkedHashMap<>(),
            BuiltinErrorDeclaration.synthesized(
                built.input().modules().get(0).ast().span()),
            List.of(deal.semantic.ir.IntrinsicKind.INT_CONVERT,
                deal.semantic.ir.IntrinsicKind.NUMBER_CONVERT,
                deal.semantic.ir.IntrinsicKind.BYTES_NEW),
            Set.of());
        check(result.project() != null && result.diagnostics().isEmpty(),
            "the fixture lowers with zero diagnostics: " + result.diagnostics());
        return new LoweredProject(result, project.root());
    }

    /**
     * Runs one lowered project through the three-consumer differential
     * matrix and returns the per-consumer escaped-failure snapshots, keyed by
     * consumer name.
     */
    private static Map<String, SemanticRuntimeModel.ErrorSnapshot> matrix(
            LoweredProject lowered, SemanticDifferentialHarness.Expectation expectation,
            String workspacePrefix) throws Exception {
        Map<String, SemanticRuntimeModel.ErrorSnapshot> snapshots =
            new LinkedHashMap<>();
        if (lowered.result().project() == null) {
            return snapshots;
        }
        Path workspace = Files.createTempDirectory(workspacePrefix);
        try {
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(lowered.result().project(),
                    lowered.result().tables(), lowered.result().registries(),
                    expectation, workspace);
            check(verdict.pass(), expectation.what() + ": the oracle and both "
                + "shared artifacts agree event-for-event with the pinned "
                + "terminal: " + verdict.failures());
            checkEq(3, verdict.runs().size(), expectation.what()
                + ": the differential matrix produced the three consumers: "
                + verdict.failures());
            String text = null;
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                if (!(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure terminal)) {
                    fail(run.consumer() + ": the terminal is the escaped DEAL "
                        + "failure; got " + run.terminal());
                    continue;
                }
                SemanticRuntimeModel.ErrorSnapshot error = terminal.error();
                snapshots.put(run.consumer(), error);
                if (text == null) {
                    text = error.text();
                } else {
                    checkEq(text, error.text(), run.consumer() + ": the complete "
                        + "nested terminal snapshot equals the first consumer's");
                }
            }
        } finally {
            deleteRecursively(workspace);
        }
        return snapshots;
    }

    private static void testMutatedRethrowMetadata(Path root) throws Exception {
        System.out.println("-- a mutated first-throw rethrow keeps the first "
            + "throw's origin and invents no cause on the oracle and both "
            + "shared artifacts --");
        Project project = writeProject(root, MUTATED_LIB_SOURCE, MUTATED_APP_SOURCE);
        LoweredProject lowered = lower(project);
        Map<String, SemanticRuntimeModel.ErrorSnapshot> snapshots = matrix(lowered,
            SemanticDifferentialHarness.Expectation.failure(
                "a mutated first-throw rethrow keeps the first throw's origin",
                List.of(), "SECOND", null), "error-identity-mutated-");
        checkEq(3, snapshots.size(), "every consumer published the escaped failure");
        for (Map.Entry<String, SemanticRuntimeModel.ErrorSnapshot> entry
                : snapshots.entrySet()) {
            String consumer = entry.getKey();
            SemanticRuntimeModel.ErrorSnapshot error = entry.getValue();
            checkEq("SECOND", error.code(), consumer + ": the rethrown failure "
                + "publishes the instance's current code");
            checkEq("second", error.message(), consumer + ": the rethrown failure "
                + "publishes the instance's current message");
            check(error.origin() != null && error.origin().endsWith("src/lib.deal:5:5"),
                consumer + ": the rethrown failure keeps the FIRST throw's own "
                    + "source location; got " + error.origin());
            checkEq(null, error.expected(), consumer + ": the first throw's absent "
                + "expected type never becomes a value");
            checkEq(null, error.actual(), consumer + ": the first throw's absent "
                + "actual kind never becomes a value");
            check(String.join(",", error.frames()).matches(
                    "lib#raise@F\\d+,app#main@F\\d+"),
                consumer + ": the first throw's frames survive the rethrow: "
                    + error.frames());
            checkEq(null, error.cause(), consumer + ": the rethrow invents no cause");
        }
        deleteRecursively(project.root());
    }

    private static void testHandlerReplacementMetadata(Path root) throws Exception {
        System.out.println("-- a handler's replacement keeps its own origin and "
            + "links the caught failure as its cause on the oracle and both "
            + "shared artifacts --");
        Project project = writeProject(root, WRAPPED_LIB_SOURCE, MUTATED_APP_SOURCE);
        LoweredProject lowered = lower(project);
        Map<String, SemanticRuntimeModel.ErrorSnapshot> snapshots = matrix(lowered,
            SemanticDifferentialHarness.Expectation.failure(
                "a handler's replacement keeps its own origin", List.of(),
                "OUTER", null), "error-identity-wrapped-");
        checkEq(3, snapshots.size(), "every consumer published the escaped failure");
        for (Map.Entry<String, SemanticRuntimeModel.ErrorSnapshot> entry
                : snapshots.entrySet()) {
            String consumer = entry.getKey();
            SemanticRuntimeModel.ErrorSnapshot error = entry.getValue();
            checkEq("OUTER", error.code(), consumer + ": the replacement's own code");
            checkEq("outer", error.message(), consumer + ": the replacement's own message");
            check(error.origin() != null && error.origin().endsWith("src/lib.deal:12:5"),
                consumer + ": the replacement raises at its own THROW origin; got "
                    + error.origin());
            checkEq(null, error.expected(), consumer + ": the replacement has no "
                + "expected type");
            checkEq(null, error.actual(), consumer + ": the replacement has no "
                + "actual kind");
            SemanticRuntimeModel.ErrorSnapshot cause = error.cause();
            check(cause != null, consumer + ": the replacement links the caught "
                + "failure as its cause");
            if (cause == null) {
                continue;
            }
            checkEq("E8001", cause.code(), consumer + ": the linked cause is the "
                + "typed E8001");
            checkEq("expected int", cause.message(), consumer
                + ": the linked cause keeps its message");
            check(cause.origin() != null && cause.origin().endsWith("src/lib.deal:3:16"),
                consumer + ": the linked cause keeps its raising origin; got "
                    + cause.origin());
            checkEq("int", cause.expected(), consumer + ": the linked cause keeps "
                + "its expected type");
            checkEq("string", cause.actual(), consumer + ": the linked cause keeps "
                + "its actual kind");
            checkEq(null, cause.cause(), consumer + ": the linked cause gains no "
                + "invented cause");
        }
        deleteRecursively(project.root());
    }

    /**
     * The JS escaped-metadata regression: the identity-preserving rethrow of
     * a mutated first-throw instance escapes with the instance's current
     * code/message and the FIRST throw's own source location — the published
     * artifact's uncaught terminal, asserted byte-exactly (not just the exit
     * code).
     */
    private static void testJsEscapedMetadata(Path root) throws Exception {
        System.out.println("-- the published JS artifact: the mutated first-throw "
            + "rethrow keeps the first throw's origin --");
        Project project = writeProject(root, MUTATED_LIB_SOURCE, MUTATED_APP_SOURCE);
        productionCompile(project, Backend.JS);
        ProcessOutcome run = runProcess(List.of("node", "app.js"), project.out());
        String lib = project.src().resolve("lib.deal").toAbsolutePath().toString();
        checkEq(1, run.exitCode(), "js: the uncaught mutated rethrow exits one: "
            + run.output());
        checkEq("", run.stdout(), "js: the uncaught terminal writes no stdout: "
            + run.output());
        checkEq("DEAL_ERROR_CODE: SECOND second at " + lib + ":5:5\n",
            run.stderr(), "js: the uncaught terminal carries the instance's "
                + "current code/message at the FIRST throw's own source "
                + "location: " + run.output());
        deleteRecursively(project.root());
    }

    // =========================================================================
    // Regression 3: the awaited error propagation
    // =========================================================================

    private static void testAwaitedPropagation(Path root) throws Exception {
        System.out.println("-- the awaited error keeps the Error instance on the "
            + "production async-export surface of all three backends --");
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM, Backend.JS)) {
            Path projectRoot = root.resolve(backend.name().toLowerCase());
            Project project = writeProject(projectRoot, fixtureSource());
            productionCompile(project, backend);
            switch (backend) {
                case LUAJIT -> {
                    Object result = new LuaJitAsyncExportInvoker().invoke(
                        new AsyncExportInvocationRequest(
                            project.out().resolve(ENTRY_MODULE + ".lua"),
                            AWAITED_EXPORT, AWAITED_DESCRIPTOR));
                    assertAwaitCompletion(backend.name(), result);
                }
                case JVM -> {
                    Path classes = project.root().resolve("classes");
                    Files.createDirectories(classes);
                    String classpath = absoluteClasspath();
                    ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
                        "-proc:none", "-cp", classpath, "-d", classes.toString(),
                        project.out().resolve("Main.java").toAbsolutePath().toString()),
                        project.out());
                    checkEq(0, javac.exitCode(), "jvm: the emitted awaited-error "
                        + "artifact compiles with javac --release 25 -proc:none: "
                        + javac.stdout() + javac.stderr());
                    if (javac.exitCode() != 0) {
                        continue;
                    }
                    Object result = new JvmAsyncExportInvoker().invoke(
                        new AsyncExportInvocationRequest(
                            classes.resolve("Main.class"), AWAITED_EXPORT,
                            AWAITED_DESCRIPTOR));
                    assertAwaitCompletion(backend.name(), result);
                }
                case JS -> {
                    writeFile(project.out(), "probe.js", JS_ASYNC_DRIVER);
                    ProcessOutcome run = runProcess(List.of("node", "probe.js"),
                        project.out());
                    checkEq(0, run.exitCode(), "js: the awaited error keeps the "
                        + "Error instance: " + run.output());
                    checkEq(EXPECTED_STDOUT + "OK|awaited|awaited\n",
                        run.stdout(), "js: the awaited-error postcondition "
                        + "marker (the invoker runs the entry terminal exactly "
                        + "once, then the export): " + run.output());
                    checkEq("", run.stderr(), "js: no diagnostics on stderr: "
                        + run.output());
                }
                default -> throw new IllegalStateException(
                    "no awaited-propagation lane for " + backend);
            }
            deleteRecursively(project.root());
        }
    }

    /**
     * The JS production async-export driver: the published artifact's own
     * {@code $rt.invokeAsyncExport} contract, so the awaited error crosses
     * the real production invoker rather than a re-implemented await.
     */
    private static final String JS_ASYNC_DRIVER = """
        "use strict";
        const $rt = require("./deal/runtime");
        const $entry = require("./main.js");
        $rt.invokeAsyncExport($entry, "test_awaited_error_identity", "string")
          .then(($result) => {
          if ($result.$ok === true) {
            process.stdout.write("OK|awaited|" + $result.$value + "\\n");
            process.exitCode = 0;
          } else if ($result.$error !== undefined) {
            process.stdout.write("DEAL_ERROR_CODE: " + $result.$error.code
              + " " + $result.$error.message + "\\n");
            process.exitCode = 1;
          } else {
            process.stdout.write("HOST_FAILURE: " + $result.$failure + "\\n");
            process.exitCode = 2;
          }
        });
        """;

    /** Asserts the awaited case completed with its declared value. */
    private static void assertAwaitCompletion(String backend, Object result) {
        switch (result) {
            case LuaJitAsyncExportInvoker.Result.Value value -> {
                checkEq(AWAITED_DESCRIPTOR, value.returnDescriptor(), backend
                    + ": the awaited completion descriptor");
                checkEq(AWAITED_VALUE_JSON, value.valueJson(), backend
                    + ": the awaited error kept the Error instance (the export's "
                    + "postconditions passed)");
            }
            case LuaJitAsyncExportInvoker.Result.DealError error -> fail(backend
                + ": the awaited assertion escaped a DEAL failure instead of "
                + "completing: " + error);
            case LuaJitAsyncExportInvoker.Result.HostFailure failure -> fail(backend
                + ": the awaited assertion was a host failure: " + failure);
            case JvmAsyncExportInvoker.Result.Value value -> {
                checkEq(AWAITED_DESCRIPTOR, value.returnDescriptor(), backend
                    + ": the awaited completion descriptor");
                checkEq(AWAITED_VALUE_JSON, value.valueJson(), backend
                    + ": the awaited error kept the Error instance (the export's "
                    + "postconditions passed)");
            }
            case JvmAsyncExportInvoker.Result.DealError error -> fail(backend
                + ": the awaited assertion escaped a DEAL failure instead of "
                + "completing: " + error);
            case JvmAsyncExportInvoker.Result.HostFailure failure -> fail(backend
                + ": the awaited assertion was a host failure: " + failure);
            default -> fail(backend + ": unexpected production invoker result "
                + result);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Error Identity Production Test (ISSUE-0881) ===\n");
        Map<String, ExpectedExecution> expected = expectedByBackend(sidecar());
        Path root = Files.createTempDirectory("deal-error-identity-");
        try {
            if (expected.get("luajit") != null) {
                testLuaJitIdentityArtifact(root.resolve("luajit"),
                    expected.get("luajit"));
            }
            if (expected.get("jvm") != null) {
                testJvmIdentityArtifact(root.resolve("jvm"), expected.get("jvm"));
            }
            if (expected.get("js") != null) {
                testJsIdentityArtifact(root.resolve("js"), expected.get("js"));
            }
            testMutatedRethrowMetadata(root.resolve("mutated"));
            testHandlerReplacementMetadata(root.resolve("wrapped"));
            testJsEscapedMetadata(root.resolve("js-metadata"));
            testAwaitedPropagation(root.resolve("awaited"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.out.println("=== Error Identity Production Tests FAILED ===");
            System.exit(1);
        }
        System.out.println("=== Error Identity Production Tests Passed ===");
    }
}
