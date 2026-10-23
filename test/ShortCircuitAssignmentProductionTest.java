package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmNames;
import deal.codegen.lua.LuaJitAsyncExportInvoker;
import deal.module.CompilationOrchestrator;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.BlockId;
import deal.semantic.ir.ChainOperandCompletion;
import deal.semantic.ir.ControlSelector;
import deal.semantic.ir.ExecutableLoweredProject;
import deal.semantic.ir.KindPayload;
import deal.semantic.ir.LoweredModuleUnit;
import deal.semantic.ir.OpId;
import deal.semantic.ir.SemanticOp;
import deal.semantic.ir.SemanticOpKind;
import deal.semantic.ir.ValueId;
import deal.test.conformance.JvmCompilation;
import deal.test.conformance.SidecarExpectations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The short-circuit assignment production regression (ISSUE-0890).
 *
 * <p>The operational semantics summary pins strict left-to-right
 * evaluation with short-circuit {@code &&}/{@code ||} (rules 1-4: an
 * assignment evaluates receiver, index, then RHS once, in order). A
 * logical right operand is lowered into its branch's selected block: the
 * branch op is a member of the enclosing block, the operand's producer is
 * a member of the selected block. The address-chain production named the
 * block-internal producer as the chain's value child, so every consumer
 * emitted the invocation outside the guard — the callee load stayed under
 * {@code if} while the call, the effects, and the throw ran
 * unconditionally (LuaJIT/JVM aborted with the skipped operand's error,
 * JavaScript died reading the unassigned callee slot).</p>
 *
 * <p>This suite drives the three published artifacts: the synchronous
 * target matrix of both operators through variable, array-element, table
 * member/index and class-field assignment (skipped throw and effects,
 * selected RHS exactly once, nested logical results,
 * receiver→key→RHS order, declaration/return controls), the awaited
 * matrix through each lane's production async-export host, and the
 * selected-right-operand abrupt fixture whose exact error value and origin
 * are the pinned postcondition. The semantic-IR half pins the repaired
 * structure itself: every chain child is a member of its chain's own
 * block, each operator/target pair names the branch as the value child,
 * and the conditional producer completes through the branch its closure
 * reproduces.</p>
 */
public final class ShortCircuitAssignmentProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** Both operators crossed with variable/array/table/class assignment. */
    private static final String TARGETS_FIXTURE =
        "variables/short-circuit-assignment-targets";

    /** The same operators on the async continuation path. */
    private static final String AWAITED_FIXTURE =
        "variables/short-circuit-assignment-awaited";

    /** The selected right operand's exact error value and origin. */
    private static final String ABRUPT_FIXTURE =
        "variables/short-circuit-assignment-abrupt";

    private static final String ENTRY_MODULE = "main";

    private static final String AWAITED_EXPORT = "awaitedShortCircuitAssignments";

    private static final String ABRUPT_EXPORT = "selectedRightOperandAbrupt";

    /** The awaited fixture's completion marker (the entry's own postcondition log). */
    private static final String AWAITED_MARKER =
        "awaited-short-circuit-assignment";

    /** The abrupt fixture's pinned error value. */
    private static final String ABRUPT_CODE = "SC_SELECTED";

    private static final String ABRUPT_MESSAGE =
        "the selected right operand executed";

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

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Project writeProject(Path root, String source) throws Exception {
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

    /**
     * The lane-independent canonical export observation every driver below
     * prints: {@code VALUE} for a completed invocation whose result the
     * envelope reports, or {@code ERROR <code>|<message>|<file>:<line>:<col>}
     * for a DEAL error value.
     */
    private static String errorLine(String code, String message, String origin) {
        return "ERROR " + code + "|" + message + "|" + origin;
    }

    private static String originOf(Path sourceFile, int line, int column) {
        return sourceFile.toAbsolutePath().normalize() + ":" + line + ":"
            + column;
    }

    // =========================================================================
    // 1. The synchronous target matrix on every published artifact
    // =========================================================================

    private static void testTargets(Path root, String backend,
            SidecarExpectations.StructuredExpectationSidecar sidecar)
            throws Exception {
        System.out.println("-- the synchronous target matrix: " + backend + " --");
        SidecarExpectations.RuntimeExpectation expectation =
            sidecar.expectationFor(backend);
        check(expectation instanceof SidecarExpectations.RuntimeExpectation.Executed,
            backend + ": the sidecar pins an executed lane");
        if (!(expectation instanceof
                SidecarExpectations.RuntimeExpectation.Executed executed)) {
            return;
        }
        checkEq("runtime-ok", executed.mode(), backend + ": the sidecar mode");
        Project project = writeProject(root, fixtureSource(TARGETS_FIXTURE));
        switch (backend) {
            case "luajit" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.LUAJIT);
                assertProductionEmission(orchestrator, "luajit");
                Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
                check(Files.isRegularFile(artifact), "luajit: the production "
                    + "compile stages the entry chunk " + artifact);
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                assertTranscript("luajit",
                    runProcess(List.of("luajit", ENTRY_MODULE + ".lua"),
                        project.out()),
                    executed.exitCode(),
                    new String(executed.stdout(), StandardCharsets.UTF_8),
                    new String(executed.stderr(), StandardCharsets.UTF_8));
            }
            case "jvm" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.JVM);
                assertProductionEmission(orchestrator, "jvm");
                String className = JvmNames.classNameFor(ENTRY_MODULE);
                Path artifact = project.out().resolve(className + ".java");
                check(Files.isRegularFile(artifact), "jvm: the production "
                    + "compile stages the entry class " + artifact);
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                if (!compileJvm(project, artifact, "jvm")) {
                    return;
                }
                String classpath = project.classes() + File.pathSeparator
                    + absoluteClasspath();
                assertTranscript("jvm", runProcess(List.of("java", "-cp",
                        classpath, className), project.out()),
                    executed.exitCode(),
                    new String(executed.stdout(), StandardCharsets.UTF_8),
                    new String(executed.stderr(), StandardCharsets.UTF_8));
            }
            case "js" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.JS);
                assertProductionEmission(orchestrator, "js");
                Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
                check(Files.isRegularFile(artifact), "js: the production "
                    + "compile stages the entry artifact " + artifact);
                check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
                    "js: the production compile deploys the runtime");
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                assertTranscript("js",
                    runProcess(List.of("node", ENTRY_MODULE + ".js"),
                        project.out()),
                    executed.exitCode(),
                    new String(executed.stdout(), StandardCharsets.UTF_8),
                    new String(executed.stderr(), StandardCharsets.UTF_8));
            }
            default -> throw new IllegalArgumentException(
                "not a production backend: " + backend);
        }
    }

    private static boolean compileJvm(Project project, Path artifact,
            String label) throws Exception {
        Files.createDirectories(project.classes());
        String classpath = absoluteClasspath();
        ProcessOutcome javac = runProcess(List.of("javac", "--release", "25",
            "-proc:none", "-cp", classpath, "-d",
            project.classes().toString(), artifact.toAbsolutePath().toString()),
            project.out());
        checkEq(0, javac.exitCode(), label + ": the emitted artifact compiles "
            + "with javac --release 25 -proc:none: " + javac.stdout()
            + javac.stderr());
        return javac.exitCode() == 0;
    }

    // =========================================================================
    // 2. The awaited matrix through each lane's production async-export host
    // =========================================================================

    /** The exact completion envelope of the awaited fixture's null completion. */
    private static String awaitedEnvelope() {
        return LuaJitAsyncExportInvoker.RESULT_PREFIX
            + "{\"status\":\"value\",\"descriptor\":\"null\",\"value\":\"null\"}";
    }

    private static String expectedAwaitedTranscript() {
        return AWAITED_MARKER + "\n" + awaitedEnvelope() + "\n";
    }

    private static void testAwaited(Path root, String backend) throws Exception {
        System.out.println("-- the awaited matrix through the " + backend
            + " production async-export host --");
        Project project = writeProject(root, fixtureSource(AWAITED_FIXTURE));
        switch (backend) {
            case "luajit" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.LUAJIT);
                assertProductionEmission(orchestrator, "luajit");
                Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
                Path driver = Path.of(LuaJitAsyncExportInvoker.DRIVER_RESOURCE)
                    .toAbsolutePath().normalize();
                check(Files.isRegularFile(artifact), "luajit: the production "
                    + "compile stages the entry chunk " + artifact);
                check(Files.isRegularFile(driver), "luajit: the production "
                    + "async-export driver exists: " + driver);
                if (!Files.isRegularFile(artifact) || !Files.isRegularFile(driver)) {
                    return;
                }
                assertTranscript("luajit awaited",
                    runProcess(List.of("luajit", driver.toString(),
                            artifact.toAbsolutePath().toString(),
                            project.out().toAbsolutePath().toString(),
                            AWAITED_EXPORT, "null"),
                        project.out(), Map.of("DEAL_DEFER_MAIN", "1")),
                    0, expectedAwaitedTranscript(), "");
            }
            case "jvm" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.JVM);
                assertProductionEmission(orchestrator, "jvm");
                String className = JvmNames.classNameFor(ENTRY_MODULE);
                Path artifact = project.out().resolve(className + ".java");
                check(Files.isRegularFile(artifact), "jvm: the production "
                    + "compile stages the entry class " + artifact);
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                if (!compileJvm(project, artifact, "jvm")) {
                    return;
                }
                String hostClass = className + "$"
                    + JvmAsyncExportInvoker.HOST_CLASS_SUFFIX;
                String classpath = project.classes() + File.pathSeparator
                    + absoluteClasspath();
                assertTranscript("jvm awaited",
                    runProcess(List.of("java", "-cp", classpath, hostClass,
                            JvmAsyncExportInvoker.HOST_ARG, AWAITED_EXPORT,
                            "null"),
                        project.out()),
                    0, expectedAwaitedTranscript(), "");
            }
            case "js" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.JS);
                assertProductionEmission(orchestrator, "js");
                Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
                check(Files.isRegularFile(artifact), "js: the production "
                    + "compile stages the entry artifact " + artifact);
                check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
                    "js: the production compile deploys the runtime");
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                writeFile(project.out(), "ShortCircuitAwaitedDriver.js",
                    jsAsyncDriver());
                assertTranscript("js awaited",
                    runProcess(List.of("node", "ShortCircuitAwaitedDriver.js"),
                        project.out()),
                    0, expectedAwaitedTranscript(), "");
            }
            default -> throw new IllegalArgumentException(
                "not a production backend: " + backend);
        }
    }

    /**
     * The JS lane's export drive: the deployed artifact's own zero-arity
     * export carrier is awaited and its checked completion is reported in
     * the same envelope the LuaJIT/JVM hosts emit, so the three lanes'
     * transcripts are byte-identical.
     */
    private static String jsAsyncDriver() {
        return "// Generated by deal.test.ShortCircuitAssignmentProductionTest.\n"
            + "// Awaits the awaited fixture's export carrier and reports its\n"
            + "// checked completion in the shared async-export envelope.\n"
            + "\"use strict\";\n"
            + "const $path = require(\"path\");\n"
            + "const $rt = require($path.resolve(\"./deal/runtime.js\"));\n"
            + "const $entry = require($path.resolve(\"./main.js\"));\n"
            + "(async () => {\n"
            + "  const $carrier = $entry[\"" + AWAITED_EXPORT + "\"];\n"
            + "  if (!$carrier || $carrier.$kind !== \"function\") {\n"
            + "    throw new Error(\"missing export carrier: " + AWAITED_EXPORT
            + "\");\n"
            + "  }\n"
            + "  await $carrier.$f();\n"
            + "  process.stdout.write(\"" + LuaJitAsyncExportInvoker.RESULT_PREFIX
            + "\" + JSON.stringify({status: \"value\", descriptor: \"null\","
            + " value: \"null\"}) + \"\\n\");\n"
            + "})().catch(($e) => {\n"
            + "  const $err = $rt.reifyError($e);\n"
            + "  process.stdout.write(\"DEAL_ERROR_CODE: \" + $err.code + \"\\n\");\n"
            + "  process.exitCode = 1;\n"
            + "});\n";
    }

    // =========================================================================
    // 3. The selected right operand's exact error value and origin
    // =========================================================================

    /**
     * The materialized source's coordinate of the fixture's selected
     * right-operand throw: the 1-based line and the 1-based column of the
     * {@code throw} keyword every artifact reports as the error origin.
     */
    private record ThrowCoordinate(int line, int column) {
    }

    private static ThrowCoordinate selectedThrowCoordinate(Path sourceFile)
            throws Exception {
        List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int at = line.indexOf("throw { code: \"" + ABRUPT_CODE + "\"");
            if (at >= 0) {
                return new ThrowCoordinate(i + 1, at + 1);
            }
        }
        throw new IllegalStateException("the abrupt fixture carries no "
            + ABRUPT_CODE + " throw in " + sourceFile);
    }

    private static void testAbrupt(Path root, String backend,
            SidecarExpectations.StructuredExpectationSidecar sidecar)
            throws Exception {
        System.out.println("-- the selected right operand's exact error: "
            + backend + " --");
        SidecarExpectations.RuntimeExpectation expectation =
            sidecar.expectationFor(backend);
        check(expectation instanceof SidecarExpectations.RuntimeExpectation.Executed,
            backend + ": the sidecar pins an executed lane");
        if (!(expectation instanceof
                SidecarExpectations.RuntimeExpectation.Executed executed)
                || executed.error() == null) {
            return;
        }
        checkEq("runtime-error", executed.mode(), backend + ": the sidecar mode");
        checkEq(ABRUPT_CODE, executed.error().code(),
            backend + ": the sidecar pins the selected right operand's code");
        checkEq(ABRUPT_MESSAGE, executed.error().message(),
            backend + ": the sidecar pins the selected right operand's message");

        Project project = writeProject(root, fixtureSource(ABRUPT_FIXTURE));
        ThrowCoordinate coordinate = selectedThrowCoordinate(project.entry());
        String expectedOrigin = originOf(project.entry(), coordinate.line(),
            coordinate.column());
        String expected = errorLine(ABRUPT_CODE, ABRUPT_MESSAGE, expectedOrigin);

        // The sidecar's corpus coordinates are the raw fixture's: the
        // header lines the harness drops are exactly the offset between the
        // materialized source and the committed fixture.
        int headerLines = rawFixtureLines(ABRUPT_FIXTURE)
            - Files.readAllLines(project.entry(), StandardCharsets.UTF_8).size();
        check(executed.error().pinsSpan(), backend + ": the sidecar pins the "
            + "error span group");
        if (executed.error().pinsSpan()) {
            checkEq(coordinate.line() + headerLines, executed.error().line(),
                backend + ": the sidecar line is the committed fixture's throw");
            checkEq(coordinate.column(), executed.error().column(),
                backend + ": the sidecar column is the throw keyword's column");
            checkEq("backend-runtime/" + ABRUPT_FIXTURE + ".deal",
                executed.error().sourceFile(), backend
                    + ": the sidecar sourceFile is the committed fixture");
        }

        switch (backend) {
            case "luajit" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.LUAJIT);
                assertProductionEmission(orchestrator, "luajit");
                Path artifact = project.out().resolve(ENTRY_MODULE + ".lua");
                check(Files.isRegularFile(artifact), "luajit: the production "
                    + "compile stages the entry chunk " + artifact);
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                writeFile(project.out(), "ShortCircuitAbruptDriver.lua",
                    luaSyncExportDriver());
                assertTranscript("luajit abrupt",
                    runProcess(List.of("luajit", "ShortCircuitAbruptDriver.lua",
                            artifact.toAbsolutePath().toString(), ENTRY_MODULE,
                            ABRUPT_EXPORT),
                        project.out(), Map.of("DEAL_DEFER_MAIN", "1")),
                    0, expected + "\n", "");
            }
            case "jvm" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.JVM);
                assertProductionEmission(orchestrator, "jvm");
                String className = JvmNames.classNameFor(ENTRY_MODULE);
                Path artifact = project.out().resolve(className + ".java");
                check(Files.isRegularFile(artifact), "jvm: the production "
                    + "compile stages the entry class " + artifact);
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                writeFile(project.out(), "ShortCircuitAbruptDriver.java",
                    jvmSyncExportDriver(className));
                Files.createDirectories(project.classes());
                String classpath = absoluteClasspath();
                ProcessOutcome javac = runProcess(List.of("javac", "--release",
                    "25", "-proc:none", "-cp", classpath, "-d",
                    project.classes().toString(),
                    artifact.toAbsolutePath().toString(),
                    project.out().resolve("ShortCircuitAbruptDriver.java")
                        .toAbsolutePath().toString()),
                    project.out());
                checkEq(0, javac.exitCode(), "jvm: the emitted artifact and its "
                    + "export driver compile with javac --release 25 -proc:none: "
                    + javac.stdout() + javac.stderr());
                if (javac.exitCode() != 0) {
                    return;
                }
                assertTranscript("jvm abrupt",
                    runProcess(List.of("java", "-cp", project.classes()
                            + File.pathSeparator + classpath,
                        "ShortCircuitAbruptDriver", ENTRY_MODULE, ABRUPT_EXPORT),
                        project.out()),
                    0, expected + "\n", "");
            }
            case "js" -> {
                CompilationOrchestrator orchestrator =
                    productionCompile(project, Backend.JS);
                assertProductionEmission(orchestrator, "js");
                Path artifact = project.out().resolve(ENTRY_MODULE + ".js");
                check(Files.isRegularFile(artifact), "js: the production "
                    + "compile stages the entry artifact " + artifact);
                check(Files.isRegularFile(project.out().resolve("deal/runtime.js")),
                    "js: the production compile deploys the runtime");
                if (!Files.isRegularFile(artifact)) {
                    return;
                }
                writeFile(project.out(), "ShortCircuitAbruptDriver.js",
                    jsSyncExportDriver());
                assertTranscript("js abrupt",
                    runProcess(List.of("node", "ShortCircuitAbruptDriver.js",
                            ABRUPT_EXPORT), project.out()),
                    0, expected + "\n", "");
            }
            default -> throw new IllegalArgumentException(
                "not a production backend: " + backend);
        }
    }

    private static int rawFixtureLines(String fixture) throws Exception {
        return Files.readAllLines(CORPUS.resolve(fixture + ".deal"),
            StandardCharsets.UTF_8).size();
    }

    /**
     * The LuaJIT lane's synchronous export drive: the chunk runs its own
     * module initialization through {@code __dealMain}, then the named
     * zero-arity export executes once and its DEAL error value is reported
     * in the lane-independent canonical line.
     */
    private static String luaSyncExportDriver() {
        return "-- Generated by deal.test.ShortCircuitAssignmentProductionTest.\n"
            + "-- Drives one zero-arity export of the published chunk and\n"
            + "-- reports its DEAL error value canonically.\n"
            + "local entry, module, name = assert(arg[1]), assert(arg[2]), assert(arg[3])\n"
            + "local ok, err = pcall(function()\n"
            + "  dofile(entry)\n"
            + "  local okMain, mainErr = __dealMain()\n"
            + "  if not okMain then error(mainErr, 0) end\n"
            + "  local surface = assert(__exportSurfaces[module][name], 'missing probe')\n"
            + "  assert(type(surface.f) == 'function', 'probe is not callable')\n"
            + "  surface.f()\n"
            + "end)\n"
            + "if ok then print('VALUE') os.exit(0) end\n"
            + "if type(err) == 'table' then\n"
            + "  print('ERROR ' .. tostring(err.code) .. '|' .. tostring(err.m)\n"
            + "    .. '|' .. tostring(err.o))\n"
            + "  os.exit(0)\n"
            + "end\n"
            + "io.stderr:write(tostring(err), '\\n') os.exit(2)\n";
    }

    /**
     * The JVM lane's synchronous export drive: the entry's own module
     * initialization runs first (the export surface is published by it),
     * then the named export executes once and its {@code DealError} is
     * reported in the lane-independent canonical line.
     */
    private static String jvmSyncExportDriver(String className) {
        return "// Generated by deal.test.ShortCircuitAssignmentProductionTest.\n"
            + "// Drives one zero-arity export of the published class and reports\n"
            + "// its DEAL error value canonically.\n"
            + "public final class ShortCircuitAbruptDriver {\n"
            + "  public static void main(String[] args) throws Exception {\n"
            + "    try {\n"
            + "      " + className + ".dealMain();\n"
            + "      Object value = deal.codegen.jvm.JvmRuntime.exportSurface(args[0])"
            + ".read(args[1]);\n"
            + "      if (!(value instanceof deal.codegen.jvm.JvmRuntime.FunctionValue fn)) {\n"
            + "        throw new IllegalStateException(\"missing probe\");\n"
            + "      }\n"
            + "      fn.fn.invoke(new Object[0]);\n"
            + "      System.out.println(\"VALUE\");\n"
            + "    } catch (Throwable t) {\n"
            + "      while ((t instanceof ExceptionInInitializerError\n"
            + "          || t instanceof java.lang.reflect.InvocationTargetException)\n"
            + "          && t.getCause() != null) {\n"
            + "        t = t.getCause();\n"
            + "      }\n"
            + "      if (!(t instanceof deal.codegen.jvm.JvmRuntime.DealError e)) {\n"
            + "        t.printStackTrace();\n"
            + "        System.exit(2);\n"
            + "        return;\n"
            + "      }\n"
            + "      System.out.println(\"ERROR \" + e.code + \"|\" + e.msg + \"|\"\n"
            + "          + String.valueOf(e.origin));\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    }

    /**
     * The JS lane's synchronous export drive: the required entry publishes
     * its export surface, the named carrier is awaited once, and its DEAL
     * error value is reported in the lane-independent canonical line.
     */
    private static String jsSyncExportDriver() {
        return "// Generated by deal.test.ShortCircuitAssignmentProductionTest.\n"
            + "// Drives one zero-arity export of the published artifact and\n"
            + "// reports its DEAL error value canonically.\n"
            + "\"use strict\";\n"
            + "const $path = require(\"path\");\n"
            + "const $rt = require($path.resolve(\"./deal/runtime.js\"));\n"
            + "const $entry = require($path.resolve(\"./main.js\"));\n"
            + "(async () => {\n"
            + "  const $carrier = $entry[process.argv[2]];\n"
            + "  if (!$carrier || $carrier.$kind !== \"function\") {\n"
            + "    throw new Error(\"missing export carrier: \" + process.argv[2]);\n"
            + "  }\n"
            + "  await $carrier.$f();\n"
            + "  process.stdout.write(\"VALUE\\n\");\n"
            + "})().catch(($e) => {\n"
            + "  const $err = $rt.reifyError($e);\n"
            + "  process.stdout.write(\"ERROR \" + $err.code + \"|\" + $err.message\n"
            + "    + \"|\" + $err.file + \":\" + $err.line + \":\" + $err.column\n"
            + "    + \"\\n\");\n"
            + "});\n";
    }

    // =========================================================================
    // 4. The repaired semantic-IR structure
    // =========================================================================

    /** The eight (operator, assignment target) pairs the fixture covers. */
    private static List<String> requiredOperatorTargetPairs() {
        List<String> pairs = new ArrayList<>();
        for (String operator : List.of("&&", "||")) {
            for (String target : List.of("VARIABLE", "ARRAY_SLOT", "TABLE_SLOT",
                    "CLASS_FIELD")) {
                pairs.add(operator + "/" + target);
            }
        }
        return pairs;
    }

    /**
     * The value child of one assignment chain: the closed shape's value
     * position ({@code valueOp}) per target kind — {@code VARIABLE} first,
     * {@code CLASS_FIELD} second, the table member write second and the
     * table index write third, the array/bytes slot third.
     */
    private static OpId valueChildId(KindPayload.AssignPayload assign) {
        List<OpId> children = assign.childOps();
        return switch (assign.targetKind()) {
            case VARIABLE -> children.get(0);
            case CLASS_FIELD -> children.get(1);
            case TABLE_SLOT -> children.size() == 3
                ? children.get(1) : children.get(2);
            case ARRAY_SLOT, BYTES_SLOT -> children.get(2);
        };
    }

    private static void testIrStructure(Path root) throws Exception {
        System.out.println("-- the repaired chain/branch ownership in the "
            + "produced unit --");
        Project project = writeProject(root, fixtureSource(TARGETS_FIXTURE));
        CompilationOrchestrator orchestrator =
            productionCompile(project, Backend.LUAJIT);
        assertProductionEmission(orchestrator, "luajit");
        ExecutableLoweredProject produced = orchestrator.producedProject();
        check(produced != null, "the production arm published the lowered "
            + "project the chain structure is read from");
        if (produced == null) {
            return;
        }
        LoweredModuleUnit unit = produced.modules().get(produced.entryModule());
        check(unit != null, "the entry module unit is present");
        if (unit == null) {
            return;
        }
        Map<OpId, SemanticOp> byId = new LinkedHashMap<>();
        for (SemanticOp op : unit.ops()) {
            byId.put(op.opId(), op);
        }
        Map<OpId, BlockId> opBlocks = unit.bodyTable().opBlocks();
        Set<OpId> structuralOwned = ChainOperandCompletion.structuralOwners(unit);

        // (a) No chain child escapes its chain's block: a child inside a
        // selected block is the flattened conditional operand the defect
        // emitted unconditionally.
        List<String> escapes = new ArrayList<>();
        int chains = 0;
        for (SemanticOp op : unit.ops()) {
            List<OpId> children = switch (op.payload()) {
                case KindPayload.AssignPayload assign -> assign.childOps();
                case KindPayload.DeletePayload delete -> delete.childOps();
                default -> List.of();
            };
            if (children.isEmpty()) {
                continue;
            }
            chains++;
            BlockId chainBlock = opBlocks.get(op.opId());
            for (OpId childId : children) {
                BlockId childBlock = opBlocks.get(childId);
                if (!Objects.equals(chainBlock, childBlock)) {
                    escapes.add(op.opId() + " (" + chainBlock + ") child "
                        + childId + " in " + childBlock);
                }
            }
        }
        check(chains > 0, "the fixture lowers address chains");
        checkEq(List.of(), escapes, "every chain child is a member of its "
            + "chain's own block");

        // (b) Every operator/target pair names the branch as the value
        // child, and the branch's selected block publishes the committed
        // value (the conditional operand itself).
        Map<String, List<SemanticOp>> byPair = new LinkedHashMap<>();
        List<String> blockValueFailures = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (!(op.payload() instanceof KindPayload.AssignPayload assign)) {
                continue;
            }
            SemanticOp valueChild = byId.get(valueChildId(assign));
            if (valueChild == null
                    || !(valueChild.payload() instanceof KindPayload.BranchPayload branch)
                    || branch.selector() == ControlSelector.IF) {
                continue;
            }
            String pair = (branch.selector() == ControlSelector.LOGICAL_AND
                    ? "&&" : "||") + "/" + assign.targetKind().name();
            byPair.computeIfAbsent(pair, key -> new ArrayList<>()).add(op);
            Set<ValueId> blockResults = new LinkedHashSet<>();
            for (OpId memberId : unit.bodyTable().blockOps()
                    .getOrDefault(branch.selectedBlock(), List.of())) {
                if (byId.get(memberId) instanceof SemanticOp member
                        && member.result() instanceof ValueId value) {
                    blockResults.add(value);
                }
            }
            if (!blockResults.contains(valueChild.result())) {
                blockValueFailures.add(pair + " " + op.opId()
                    + ": the selected block publishes no op for the committed "
                    + "value " + valueChild.result());
            }
        }
        List<String> seenPairs = new ArrayList<>(byPair.keySet());
        List<String> expectedPairs = requiredOperatorTargetPairs();
        seenPairs.sort(null);
        expectedPairs.sort(null);
        checkEq(expectedPairs, seenPairs,
            "both operators cross all four assignment targets with the branch "
                + "as the chain's value child");
        checkEq(List.of(), blockValueFailures, "each logical chain's selected "
            + "block publishes the committed value");

        // (c) Every branch named as a chain child is payload-owned by the
        // chain, so the block walk executes it exactly once (under its own
        // chain arm).
        List<String> unowned = new ArrayList<>();
        for (List<SemanticOp> chainList : byPair.values()) {
            for (SemanticOp chain : chainList) {
                for (OpId childId : ((KindPayload.AssignPayload) chain.payload())
                        .childOps()) {
                    if (byId.get(childId) instanceof SemanticOp child
                            && child.kind() == SemanticOpKind.BRANCH
                            && !structuralOwned.contains(child.opId())) {
                        unowned.add(child.opId().toString());
                    }
                }
            }
        }
        checkEq(List.of(), unowned, "every logical chain child branch is "
            + "payload-owned by its chain");

        // (d) The operand closure of a logical call argument reproduces the
        // branch: the conditional producer inside the selected block never
        // joins the closure (it is emitted by the block), and the branch
        // itself does.
        int operandClosureChains = 0;
        Set<ControlSelector> closureSelectors = new LinkedHashSet<>();
        List<String> closureFailures = new ArrayList<>();
        for (SemanticOp op : unit.ops()) {
            if (!(op.payload() instanceof KindPayload.AssignPayload assign)) {
                continue;
            }
            SemanticOp valueChild = byId.get(valueChildId(assign));
            if (valueChild == null
                    || valueChild.kind() != SemanticOpKind.CALL) {
                continue;
            }
            List<SemanticOp> closure = ChainOperandCompletion.operandProducersOf(
                valueChild, unit, structuralOwned);
            SemanticOp branch = closure.stream()
                .filter(member -> member.payload() instanceof KindPayload.BranchPayload
                    b && b.selector() != ControlSelector.IF)
                .findFirst().orElse(null);
            if (branch == null) {
                closureFailures.add(valueChild.opId()
                    + ": the operand closure carries no logical branch");
                continue;
            }
            operandClosureChains++;
            closureSelectors.add(((KindPayload.BranchPayload) branch.payload())
                .selector());
            BlockId selected =
                ((KindPayload.BranchPayload) branch.payload()).selectedBlock();
            Set<OpId> selectedMembers = new LinkedHashSet<>(
                unit.bodyTable().blockOps().getOrDefault(selected, List.of()));
            List<String> inside = new ArrayList<>();
            for (SemanticOp member : closure) {
                if (selectedMembers.contains(member.opId())) {
                    inside.add(member.opId().toString());
                }
            }
            if (!inside.isEmpty()) {
                closureFailures.add(branch.opId()
                    + ": the closure executes selected-block ops directly "
                    + inside);
            }
        }
        check(operandClosureChains >= 2, "the call-argument assignments lower "
            + "their logical argument as a chain-visible branch "
            + "(observed " + operandClosureChains + ")");
        checkEq(Set.of(ControlSelector.LOGICAL_AND, ControlSelector.LOGICAL_OR),
            closureSelectors, "both operators cross the call-argument "
                + "operand closure");
        checkEq(List.of(), closureFailures, "the operand closure reproduces "
            + "the branch instead of flattening its selected block");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Short-Circuit Assignment Production Test "
            + "(ISSUE-0890) ===\n");
        SidecarExpectations.StructuredExpectationSidecar targets =
            sidecar(TARGETS_FIXTURE);
        SidecarExpectations.StructuredExpectationSidecar awaited =
            sidecar(AWAITED_FIXTURE);
        SidecarExpectations.StructuredExpectationSidecar abrupt =
            sidecar(ABRUPT_FIXTURE);
        for (String backend : List.of("luajit", "jvm", "js")) {
            SidecarExpectations.RuntimeExpectation pin = awaited.expectationFor(backend);
            check(pin instanceof SidecarExpectations.RuntimeExpectation.Executed
                    && pin.mode().equals("runtime-ok"),
                "the awaited fixture pins the " + backend + " runtime-ok lane");
            if (pin instanceof SidecarExpectations.RuntimeExpectation.Executed
                    executed) {
                checkEq(AWAITED_MARKER + "\n",
                    new String(executed.stdout(), StandardCharsets.UTF_8),
                    backend + ": the awaited fixture's sidecar pins its "
                        + "completion marker");
                checkEq("", new String(executed.stderr(), StandardCharsets.UTF_8),
                    backend + ": the awaited fixture pins an empty stderr");
            }
        }
        Path root = Files.createTempDirectory("deal-short-circuit-assignment-");
        try {
            testIrStructure(root.resolve("ir"));
            for (String backend : List.of("luajit", "jvm", "js")) {
                testTargets(root.resolve("targets-" + backend), backend, targets);
                testAwaited(root.resolve("awaited-" + backend), backend);
                testAbrupt(root.resolve("abrupt-" + backend), backend, abrupt);
            }
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Short-Circuit Assignment Production Test Passed ===");
    }

    private ShortCircuitAssignmentProductionTest() {
        // Static suite; no instances.
    }
}
