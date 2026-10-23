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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The intrinsic-shadowing regression (ISSUE-0878): a direct call of a
 * lexically shadowed {@code int}/{@code number} name
 * dispatches the binding the call position resolves — the local, the
 * parameter, the enclosing-block or the catch binding — on every
 * backend, and only the genuinely unshadowed name lowers to the
 * conversion/allocation intrinsic (docs/spec-v1.3.md §Name resolution:
 * locals, parameters and enclosing scopes precede module and import
 * bindings, which precede the compiler intrinsics).
 *
 * <p>The fixture's own exact values are the oracle: every case pins its
 * expected value and throws the deterministic {@code TEST_FAIL} code on
 * a misdispatch, so the sidecar's runtime-ok terminal (empty streams,
 * exit 0) fails a backend that converts instead of calling, that skips
 * a callee or argument effect, or that leaks an allocation value into
 * the int-typed call position.</p>
 *
 * <p>Each published artifact is inspected beside its execution: exactly
 * the fixture's genuine intrinsic operations carry a static
 * {@code INT_CONVERT}/{@code NUMBER_CONVERT}
 * emission, no shadowed call position carries one, a shadowed position
 * carries the call origin of the value's own dispatch, and a genuine
 * intrinsic position carries exactly one emission of its own kind.</p>
 */
public final class IntrinsicShadowingProductionTest {

    private static final Path CONFORMANCE = Path.of("test", "conformance");

    private static final Path CORPUS = CONFORMANCE.resolve("backend-runtime");

    /** The behavior matrix fixture and its three-backend oracle. */
    private static final String CONTROLS_FIXTURE = "types/intrinsic-shadowing-controls";

    /** The module-level declaration precedence fixture. */
    private static final String MODULE_FIXTURE = "types/intrinsic-shadowing-module-bytes";

    /** The import-alias precedence fixture and its companion module. */
    private static final String IMPORT_FIXTURE = "types/intrinsic-shadowing-import-bytes";
    private static final String IMPORT_COMPANION = "types/intrinsic-shadowing-bytes-lib";

    private static final String ENTRY_MODULE = "main";

    private static final List<String> BACKENDS = List.of("luajit", "jvm", "js");

    /** One genuine intrinsic operation of the controls fixture. */
    private record IntrinsicSite(String kind, int line, int column) {
    }

    /**
     * The controls fixture's genuine (unshadowed) intrinsic operations, in
     * source order: the pinned line:column of each call expression. Every
     * one of them is the only static conversion/allocation emission the
     * fixture may carry.
     */
    private static final List<IntrinsicSite> INTRINSIC_SITES = List.of(
        new IntrinsicSite("INT_CONVERT", 62, 7),     // nestedScopes: int(1.0)
        new IntrinsicSite("INT_CONVERT", 71, 10),    // nestedScopes: int(5.0)
        new IntrinsicSite("INT_CONVERT", 95, 24),    // declarationOrder: int(2.0)
        new IntrinsicSite("INT_CONVERT", 110, 28),   // forHeadCounter: int(3.0)
        new IntrinsicSite("INT_CONVERT", 172, 27),   // intrinsicResults: int(2.0)
        new IntrinsicSite("NUMBER_CONVERT", 173, 25)); // intrinsicResults: number(7)

    /** One shadowed direct call of the controls fixture. */
    private record ShadowedCall(int line, int column, String message) {
    }

    /**
     * The controls fixture's shadowed direct calls, in source order: every
     * position dispatches a user binding, so none of them may carry a
     * conversion/allocation emission.
     */
    private static final List<ShadowedCall> SHADOWED_CALLS = List.of(
        new ShadowedCall(17, 7, "local number shadow"),
        new ShadowedCall(20, 10, "local number shadow result"),
        new ShadowedCall(25, 7, "parameter number shadow"),
        new ShadowedCall(28, 10, "parameter number shadow result"),
        new ShadowedCall(34, 7, "local number shadow"),
        new ShadowedCall(37, 10, "local number shadow result"),
        new ShadowedCall(44, 7, "local bytes binding"),
        new ShadowedCall(47, 10, "local bytes binding result"),
        new ShadowedCall(57, 11, "enclosing shadow in a nested block"),
        new ShadowedCall(67, 9, "sibling block shadow"),
        new ShadowedCall(84, 9, "catch-block shadow"),
        new ShadowedCall(87, 14, "catch-block shadow result"),
        new ShadowedCall(100, 7, "declared shadow"),
        new ShadowedCall(103, 19, "declared shadow result"),
        new ShadowedCall(119, 7, "initial shadow value"),
        new ShadowedCall(123, 7, "reassigned shadow value"),
        new ShadowedCall(126, 10, "reassigned shadow value result"),
        new ShadowedCall(138, 23, "captured callee and argument effects"),
        new ShadowedCall(142, 24, "second captured-callee call"),
        new ShadowedCall(191, 38, "captured shadow callee"));

    /** The static conversion/allocation emission helpers, per backend. */
    private static final List<String> LUAJIT_CONVERSION_HELPERS =
        List.of("__intConv(", "__numConv(");
    private static final List<String> JVM_CONVERSION_HELPERS =
        List.of("JvmRuntime.intConv(", "JvmRuntime.numConv(");
    private static final List<String> JS_CONVERSION_HELPERS =
        List.of("$rt.intConvert((", "$rt.numberConvert((");

    /**
     * The JavaScript renderer's call surfaces that carry a call origin:
     * the function-value carrier call ({@code .$f}), the cross-module
     * declared-body invocation ({@code $rt.callExport}), and the async
     * export invocation.
     */
    private static final List<String> JS_CALL_MARKERS = List.of(
        ".$f(", "$rt.callExport(", "$rt.invokeAsyncExport(");

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
    // Fixtures, sidecars, and the production invocation
    // =========================================================================

    private record Project(Path root, Path src, Path entry, Path out) {
    }

    private static String fixtureSource(String fixture, String corpusPath)
            throws Exception {
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(fixture + ".deal"),
                StandardCharsets.UTF_8), corpusPath);
    }

    private static SidecarExpectations.StructuredExpectationSidecar sidecar(
            String fixture) throws Exception {
        Path file = CORPUS.resolve(fixture + ".expect.json");
        check(Files.isRegularFile(file), "the fixture carries its structured "
            + "expectation sidecar: " + file);
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
            if (!(expectation instanceof SidecarExpectations.RuntimeExpectation.Executed
                    executed)) {
                continue;
            }
            checkEq("runtime-ok", executed.mode(),
                fixture + ": the sidecar's " + backend + " mode");
            checkEq("", new String(executed.stdout(), StandardCharsets.UTF_8),
                fixture + ": the sidecar pins an empty " + backend + " stdout");
            checkEq("", new String(executed.stderr(), StandardCharsets.UTF_8),
                fixture + ": the sidecar pins an empty " + backend + " stderr");
            checkEq(0, executed.exitCode(),
                fixture + ": the sidecar pins the " + backend + " zero exit code");
            if (pinned == null) {
                pinned = new ExpectedExecution(executed.stdout(), executed.stderr(),
                    executed.exitCode());
            } else {
                checkEq(new String(pinned.stdout(), StandardCharsets.UTF_8),
                    new String(executed.stdout(), StandardCharsets.UTF_8),
                    fixture + ": every lane pins one transcript");
            }
        }
        return pinned;
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, String source,
            Map<String, String> companions) throws Exception {
        writeFile(root, "src/" + ENTRY_MODULE + ".deal", source);
        for (Map.Entry<String, String> companion : companions.entrySet()) {
            writeFile(root, "src/" + companion.getKey() + ".deal",
                companion.getValue());
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
            CompilationOrchestrator orchestrator, String fixture, String backend) {
        check(orchestrator.diagnostics().isEmpty(), fixture + "/" + backend
            + ": the production compile reports zero diagnostics: "
            + orchestrator.diagnostics());
        checkEq(1, orchestrator.semanticEmissionCount(), fixture + "/" + backend
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

    private static void assertExecution(String fixture, String backend,
            ProcessOutcome run, ExpectedExecution expected) {
        check(!run.stdoutTruncated() && !run.stderrTruncated(),
            fixture + "/" + backend + ": the artifact's streams are complete");
        checkEq(expected.exitCode(), run.exitCode(), fixture + "/" + backend
            + ": the artifact exit code: " + run.stdout() + run.stderr());
        checkEq(new String(expected.stdout(), StandardCharsets.UTF_8),
            run.stdout(), fixture + "/" + backend + ": the artifact stdout");
        checkEq(new String(expected.stderr(), StandardCharsets.UTF_8),
            run.stderr(), fixture + "/" + backend + ": the artifact stderr");
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
    // The emitted artifacts' static conversion sites
    // =========================================================================

    /**
     * The artifact's static conversion emissions: one entry per
     * emitted {@code INT_CONVERT}/{@code NUMBER_CONVERT}
     * operation that is a direct intrinsic call (never the runtime's
     * value-carried dispatch arm, which reports the {@code CALL} event
     * kind and is not a static classification).
     */
    private static List<String> conversionEmissions(String backend,
            String artifact) {
        List<String> emissions = new ArrayList<>();
        for (String line : artifact.split("\n", -1)) {
            boolean conversion = false;
            for (String helper : switch (backend) {
                case "luajit" -> LUAJIT_CONVERSION_HELPERS;
                case "jvm" -> JVM_CONVERSION_HELPERS;
                default -> JS_CONVERSION_HELPERS;
            }) {
                conversion |= line.contains(helper);
            }
            if (!conversion) {
                continue;
            }
            if (backend.equals("js") || line.contains("\"INTRINSIC_CALL\"")) {
                emissions.add(line);
            }
        }
        return emissions;
    }

    /**
     * The artifact's origin text for one source position: the backends
     * render the lowering origin as {@code <file>:line:column} inside the
     * emitted operation (JavaScript splits the same origin into the file,
     * line and column arguments).
     */
    private static String originText(String backend, int line, int column) {
        return backend.equals("js")
            ? ", " + line + ", " + column + ")"
            : ":" + line + ":" + column + "\"";
    }

    private static String conversionKindOf(String emission) {
        if (emission.contains("intConv") || emission.contains("intConvert")) {
            return "INT_CONVERT";
        }
        if (emission.contains("numConv") || emission.contains("numberConvert")) {
            return "NUMBER_CONVERT";
        }
        throw new AssertionError("unknown conversion emission: " + emission);
    }

    /**
     * True iff the artifact carries the shadowed call's own dispatch origin
     * (the current-value call the position's binding owns) at that
     * position: the LuaJIT and JVM chunks record the call origin, and the
     * JavaScript renderer passes the same origin to the carrier call.
     */
    private static boolean carriesCallOrigin(String backend, String artifact,
            int line, int column) {
        String origin = originText(backend, line, column);
        for (String text : artifact.split("\n", -1)) {
            if (!text.contains(origin)) {
                continue;
            }
            switch (backend) {
                case "luajit" -> {
                    if (text.contains("__callOrigins")) {
                        return true;
                    }
                }
                case "jvm" -> {
                    if (text.contains("JvmRuntime.callOrigins")) {
                        return true;
                    }
                }
                default -> {
                    for (String marker : JS_CALL_MARKERS) {
                        if (text.contains(marker)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String readArtifact(Path file) throws Exception {
        check(Files.isRegularFile(file), "the production compile stages "
            + file);
        return Files.isRegularFile(file)
            ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /**
     * The artifact-level expectation of one fixture: the exact number of
     * static conversion/allocation emissions it may carry (its genuine
     * intrinsic operations) and the direct-call positions that must
     * dispatch a user binding.
     */
    private record ArtifactExpectation(String fixture, int conversions,
                                       List<IntrinsicSite> sites,
                                       List<ShadowedCall> shadowedCalls) {
    }

    private static final ArtifactExpectation CONTROLS_EXPECTATION =
        new ArtifactExpectation(CONTROLS_FIXTURE, 0, List.of(),
            java.util.stream.Stream.concat(SHADOWED_CALLS.stream(),
                INTRINSIC_SITES.stream().map(site -> new ShadowedCall(
                    site.line(), site.column(), "ordinary module callable"))).toList());

    /**
     * The module-level declaration fixture calls its own {@code bytes}
     * function: the module admits no static conversion emission at all,
     * and the call at 5:7 dispatches the declared body.
     */
    private static final ArtifactExpectation MODULE_EXPECTATION =
        new ArtifactExpectation(MODULE_FIXTURE, 0, List.of(),
            List.of(new ShadowedCall(5, 7, "module-level bytes declaration")));

    /**
     * The import-alias fixture calls the imported module's export: the
     * alias (name-resolution step 4) owns the name, so no static
     * allocation emission exists and the call at 5:7 is the imported
     * function's invocation.
     */
    private static final ArtifactExpectation IMPORT_EXPECTATION =
        new ArtifactExpectation(IMPORT_FIXTURE, 0, List.of(),
            List.of(new ShadowedCall(5, 7, "import alias named bytes")));

    private static void inspectArtifact(ArtifactExpectation expectation,
            String backend, String artifact) {
        List<String> emissions = conversionEmissions(backend, artifact);
        checkEq(expectation.conversions(), emissions.size(), backend
            + ": the " + expectation.fixture() + " artifact carries exactly "
            + expectation.conversions() + " static conversion/allocation "
            + "emissions: " + emissions);
        for (IntrinsicSite site : expectation.sites()) {
            String origin = originText(backend, site.line(), site.column());
            List<String> atSite = new ArrayList<>();
            for (String emission : emissions) {
                if (emission.contains(origin)) {
                    atSite.add(emission);
                }
            }
            checkEq(1, atSite.size(), backend + ": the genuine "
                + site.kind() + " at " + site.line() + ":" + site.column()
                + " carries exactly one static conversion: " + atSite);
            if (atSite.size() == 1) {
                checkEq(site.kind(), conversionKindOf(atSite.get(0)), backend
                    + ": the genuine operation at " + site.line() + ":"
                    + site.column() + " emits its own conversion kind");
            }
        }
        for (ShadowedCall call : expectation.shadowedCalls()) {
            String origin = originText(backend, call.line(), call.column());
            boolean emitted = false;
            for (String emission : emissions) {
                emitted |= emission.contains(origin);
            }
            check(!emitted, backend + ": the shadowed call '"
                + call.message() + "' at " + call.line() + ":" + call.column()
                + " carries no intrinsic conversion");
            check(carriesCallOrigin(backend, artifact, call.line(), call.column()),
                backend + ": the shadowed call '" + call.message() + "' at "
                + call.line() + ":" + call.column()
                + " dispatches through its own call origin");
        }
    }

    // =========================================================================
    // The three production backends
    // =========================================================================

    private static void runFixture(Path root, ArtifactExpectation expectation,
            Map<String, String> companions) throws Exception {
        String fixture = expectation.fixture();
        System.out.println();
        System.out.println("== " + fixture + " ==");
        ExpectedExecution expected = expectedExecution(fixture);
        String source = fixtureSource(fixture, fixture + ".deal");
        Map<String, String> companionSources = new LinkedHashMap<>();
        for (Map.Entry<String, String> companion : companions.entrySet()) {
            companionSources.put(companion.getKey(), fixtureSource(
                companion.getValue(), companion.getValue() + ".deal"));
        }

        // LuaJIT: the emitted chunk and its real luajit run.
        Project luajit = writeProject(root.resolve("luajit"), source,
            companionSources);
        CompilationOrchestrator luajitOrchestrator =
            productionCompile(luajit, Backend.LUAJIT);
        assertProductionEmission(luajitOrchestrator, fixture, "luajit");
        String chunk = readArtifact(luajit.out().resolve(ENTRY_MODULE + ".lua"));
        inspectArtifact(expectation, "luajit", chunk);
        assertExecution(fixture, "luajit",
            runProcess(List.of("luajit", ENTRY_MODULE + ".lua"), luajit.out()),
            expected);

        // JVM: the published class under javac and java.
        Project jvm = writeProject(root.resolve("jvm"), source, companionSources);
        CompilationOrchestrator jvmOrchestrator =
            productionCompile(jvm, Backend.JVM);
        assertProductionEmission(jvmOrchestrator, fixture, "jvm");
        String className = JvmBackend.classNameFor(ENTRY_MODULE);
        Path javaArtifact = jvm.out().resolve(className + ".java");
        String java = readArtifact(javaArtifact);
        inspectArtifact(expectation, "jvm", java);
        Path classes = jvm.root().resolve("classes");
        Files.createDirectories(classes);
        String classpath = absoluteClasspath();
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.list(jvm.out())) {
            for (Path path : walk.sorted().toList()) {
                if (path.toString().endsWith(".java")) {
                    javaFiles.add(path.toAbsolutePath());
                }
            }
        }
        List<String> javacCommand = new ArrayList<>(List.of("javac", "--release",
            "25", "-proc:none", "-cp", classpath, "-d", classes.toString()));
        for (Path javaFile : javaFiles) {
            javacCommand.add(javaFile.toString());
        }
        ProcessOutcome javac = runProcess(javacCommand, jvm.out());
        checkEq(0, javac.exitCode(), "jvm: the emitted artifact compiles with "
            + "javac --release 25 -proc:none: " + javac.stdout() + javac.stderr());
        assertExecution(fixture, "jvm", runProcess(List.of("java", "-ea", "-cp",
            classes + File.pathSeparator + classpath, className), jvm.out()),
            expected);

        // JS: the published module under node.
        Project js = writeProject(root.resolve("js"), source, companionSources);
        CompilationOrchestrator jsOrchestrator = productionCompile(js, Backend.JS);
        assertProductionEmission(jsOrchestrator, fixture, "js");
        String module = readArtifact(js.out().resolve(ENTRY_MODULE + ".js"));
        inspectArtifact(expectation, "js", module);
        check(Files.isRegularFile(js.out().resolve("deal/runtime.js")),
            "js: the production compile deploys the runtime");
        assertExecution(fixture, "js",
            runProcess(List.of("node", ENTRY_MODULE + ".js"), js.out()), expected);
    }

    // =========================================================================
    // The name-resolution negatives stay real errors
    // =========================================================================

    /**
     * A compile-error fixture's production compile: the expected code must
     * be the only error diagnostic the compile reports, so a corrected
     * shadowing rule never turns a genuine redeclaration or a non-callable
     * module alias into an accepted program.
     */
    private static void assertCompileError(Path root, String fixture,
            String corpusPath, String expectedCode,
            Map<String, String> companions) throws Exception {
        String source = ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CONFORMANCE.resolve(corpusPath),
                StandardCharsets.UTF_8), corpusPath);
        Map<String, String> companionSources = new LinkedHashMap<>();
        for (Map.Entry<String, String> companion : companions.entrySet()) {
            String companionPath = "frontend/modules/" + companion.getValue()
                + ".deal";
            companionSources.put(companion.getKey(),
                ConformanceHarnessMetadata.productionCorpusSource(
                    Files.readString(CONFORMANCE.resolve(companionPath),
                        StandardCharsets.UTF_8), companionPath));
        }
        Project project = writeProject(root, source, companionSources);
        CompilationOrchestrator orchestrator = productionCompile(project,
            Backend.LUAJIT);
        List<String> codes = new ArrayList<>();
        for (var diagnostic : orchestrator.diagnostics()) {
            if ("error".equals(diagnostic.severity())) {
                codes.add(diagnostic.code());
            }
        }
        checkEq(List.of(expectedCode), codes, fixture
            + ": the production compile reports exactly the pinned "
            + expectedCode + " error");
        checkEq(0, orchestrator.semanticEmissionCount(), fixture
            + ": a rejected input stages no project artifact");
    }

    /**
     * The two name-resolution negatives: a same-scope redeclaration of a
     * shadowing {@code int} binding is the ordinary E2002, and calling an
     * import alias named {@code bytes} is the imported module value's
     * not-callable E3008 instead of the allocation intrinsic
     * (docs/spec-v1.3.md §Name resolution — module/import bindings
     * precede the compiler intrinsics, and same-scope redeclaration stays
     * an error).
     */
    private static void testNameResolutionNegatives(Path root) throws Exception {
        System.out.println();
        System.out.println("== name-resolution negatives ==");
        assertCompileError(root.resolve("redeclaration"),
            "variables/redeclaration-shadowing-int-e2002",
            "frontend/variables/redeclaration-shadowing-int-e2002.deal",
            "E2002", Map.of());
        assertCompileError(root.resolve("import-alias"),
            "modules/import-alias-intrinsic-name-e3008",
            "frontend/modules/import-alias-intrinsic-name-e3008.deal",
            "E3008", Map.of("shadow_lib", "shadow_lib"));
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Intrinsic Shadowing Production Test "
            + "(ISSUE-0878) ===\n");
        Path root = Files.createTempDirectory("deal-intrinsic-shadowing-");
        try {
            runFixture(root, CONTROLS_EXPECTATION, Map.of());
            runFixture(root, MODULE_EXPECTATION, Map.of());
            runFixture(root, IMPORT_EXPECTATION,
                Map.of("intrinsic-shadowing-bytes-lib", IMPORT_COMPANION));
            testNameResolutionNegatives(root);
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Intrinsic Shadowing Production Test Passed ===");
    }
}
