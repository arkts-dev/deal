package deal.test;

import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.lua.AsyncExportInvocationRequest;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * ISSUE-0887: the invalidated narrowed {@code Error} value is admitted before
 * the throw machinery consumes it, on every production backend and through
 * every production async invoker.
 *
 * <p>docs/spec-v1.4.md Nullable semantics keeps calls non-invalidating on
 * purpose, so a capturing closure can clear the binding while the refinement
 * claim still holds. The {@code THROW} operand is a consumed value like the
 * arithmetic operand, condition, index receiver/key, table member receiver,
 * {@code .length} receiver, for-of iterable and {@code has} receiver
 * positions, so the read is validated against the refined descriptor at its
 * own origin before any backend dereferences the {@code Error} value's
 * transport. A cleared binding therefore raises the canonical typed-boundary
 * projection (E8001, {@code expected class instance}, {@code class}/
 * {@code null}) as a catchable DEAL error — never a backend-native nil index
 * error (LuaJIT) or host null dereference (JVM) that escapes the enclosing
 * DEAL {@code try} and reaches the async invoker as an infrastructure
 * failure.</p>
 *
 * <p>Three corpus fixtures carry the evidence and are executed by the corpus
 * conformance lane on all three production backends:
 * {@code types/narrowed-capture-throw-e8001} (the synchronous throw whose
 * terminal is the pinned canonical failure),
 * {@code types/narrowed-capture-throw-controls} (the sync, parenthesized,
 * caller-catch, unchanged non-null, rethrow and once-only operands with the
 * caught async operand) and
 * {@code types/narrowed-capture-throw-async-e8001} (the escaping async
 * operand). This regression drives the async operand through the
 * <em>production async invokers</em> — the surface the reported defect
 * misclassified:</p>
 * <ul>
 *   <li>{@code types/narrowed-capture-throw-async-e8001}'s
 *       {@code test_async_throw_admission} must reach the LuaJIT, JVM and JS
 *       invokers as the DEAL error variant carrying the exact canonical tuple
 *       (code, message, source range, expected/actual), never as a host or
 *       infrastructure failure;</li>
 *   <li>the controls fixture's {@code test_async_throw_caught} must reach
 *       the same invokers as a checked completion, so the caught canonical
 *       error inside the async body is not an infrastructure failure
 *       either.</li>
 * </ul>
 */
public final class NarrowedThrowAdmissionProductionTest {

    private static final Path CORPUS = Path.of("test", "conformance",
        "backend-runtime");

    /** The escaping async operand fixture and its produced export. */
    private static final String ASYNC_ESCAPE = "types/narrowed-capture-throw-async-e8001";

    /** The controls fixture whose async operand is caught inside the body. */
    private static final String ASYNC_CAUGHT = "types/narrowed-capture-throw-controls";

    /** The escaping fixture's declared export and its return type. */
    private static final String ESCAPE_EXPORT = "test_async_throw_admission";

    private static final String ESCAPE_DESCRIPTOR = "null";

    /** The controls fixture's declared export and its return type. */
    private static final String CAUGHT_EXPORT = "test_async_throw_caught";

    private static final String CAUGHT_DESCRIPTOR = "number";

    /** The canonical tuple of the cleared-binding admission. */
    private static final String EXPECTED_CODE = "E8001";

    private static final String EXPECTED_MESSAGE = "expected class instance";

    private static final String EXPECTED_TYPE = "class";

    private static final String ACTUAL_KIND = "null";

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
    // The fixture sources and their canonical read position
    // =========================================================================

    /** The fixture source the production compilers consume (headers stripped). */
    private static String fixtureSource(String corpusPath) throws Exception {
        String corpusFile = corpusPath + ".deal";
        return ConformanceHarnessMetadata.productionCorpusSource(
            Files.readString(CORPUS.resolve(corpusFile), StandardCharsets.UTF_8),
            corpusFile);
    }

    /** A one-based source position of a marker inside a source text. */
    private record Position(int line, int column) {
    }

    /**
     * The one-based line and column of {@code needle} on the first line of
     * {@code source} that carries {@code lineMarker}, so the pinned failure
     * range is the fixture's own read position rather than a copied literal.
     */
    private static Position positionOf(String source, String lineMarker,
            String needle) {
        List<String> lines = source.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.contains(lineMarker)) {
                continue;
            }
            int column = line.indexOf(needle);
            if (column < 0) {
                continue;
            }
            return new Position(index + 1, column + 1);
        }
        throw new IllegalStateException("the fixture carries no '" + lineMarker
            + "' line with '" + needle + "'");
    }

    // =========================================================================
    // The production compile and the three invoker lanes
    // =========================================================================

    private record Project(Path root, Path src, Path entry, Path out) {
    }

    private static Project writeProject(Path root, String source)
            throws Exception {
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("main.deal"), source,
            StandardCharsets.UTF_8);
        return new Project(root, src, src.resolve("main.deal"),
            root.resolve("out"));
    }

    private static CompilerInvocation productionInvocation() {
        return CompilerProfileProvider.resolve(
            ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static void productionCompile(Project project, Backend backend)
            throws Exception {
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(
            project.entry().toAbsolutePath(), project.out(), false, false,
            false, false, backend, null,
            List.of(project.src().toAbsolutePath()),
            project.root().toAbsolutePath(), null, productionInvocation());
        check(orchestrator.compile(), backend + ": the production compile of "
            + project.entry().getFileName() + " succeeds: "
            + orchestrator.diagnostics());
        check(orchestrator.diagnostics().isEmpty(), backend + ": the production "
            + "compile reports zero diagnostics: " + orchestrator.diagnostics());
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
            JvmCompilation.Result compiled =
                JvmCompilation.compile(command, workDir);
            return new ProcessOutcome(compiled.exitCode(), compiled.stdout(),
                compiled.stderr());
        }
        BoundedProcess.BoundedResult run =
            BoundedProcess.runCaptured(command, workDir);
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
     * The JS production async-export driver: the published artifact's own
     * {@code $rt.invokeAsyncExport} contract, printed in the one closed
     * framing this regression parses — a checked completion, the reified
     * DEAL error's complete tuple, or the host/infrastructure failure
     * reason.
     */
    private static String jsDriver(String export, String descriptor) {
        return """
            "use strict";
            const $rt = require("./deal/runtime");
            const $entry = require("./main.js");
            $rt.invokeAsyncExport($entry, %s, %s).then(($result) => {
              if ($result.$ok === true) {
                process.stdout.write("OK|" + JSON.stringify($result.$value) + "\\n");
                process.exitCode = 0;
              } else if ($result.$error !== undefined) {
                const $e = $result.$error;
                process.stdout.write("ERR|" + $e.code + "|" + $e.message + "|"
                  + String($e.file) + "|" + String($e.line) + "|"
                  + String($e.column) + "|" + String($e.expected) + "|"
                  + String($e.actual) + "\\n");
                process.exitCode = 0;
              } else {
                process.stdout.write("HOST|" + $result.$failure + "\\n");
                process.exitCode = 0;
              }
            });
            """.formatted(quote(export), quote(descriptor));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // =========================================================================
    // Regression 1: the escaping async operand reaches the invoker as a DEAL error
    // =========================================================================

    private static void testEscapingAsyncInvoker(Path root) throws Exception {
        System.out.println("-- the escaping async throw operand reaches the "
            + "production async invokers as the canonical DEAL error --");
        String source = fixtureSource(ASYNC_ESCAPE);
        Position read = positionOf(source, "throw e;", "e;");
        System.out.println("   the pinned read position is " + read.line() + ":"
            + read.column());
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM, Backend.JS)) {
            Project project = writeProject(
                root.resolve(backend.name().toLowerCase()), source);
            productionCompile(project, backend);
            switch (backend) {
                case LUAJIT -> {
                    Invocation invocation = invokeLuaJit(project, ESCAPE_EXPORT,
                        ESCAPE_DESCRIPTOR);
                    if (!invocation.completed("luajit", "the escaping async "
                            + "throw operand")) {
                        break;
                    }
                    if (invocation.result()
                            instanceof LuaJitAsyncExportInvoker.Result.DealError error) {
                        assertEscapedTuple("luajit", error.code(), error.message(),
                            error.file(), error.line(), error.column(),
                            error.expected(), error.actual(), error.frames(), read,
                            project);
                    } else {
                        fail("luajit: the escaping async operand produced the DEAL "
                            + "error variant; got " + invocation.result());
                    }
                }
                case JVM -> {
                    Invocation invocation = invokeJvm(project, ESCAPE_EXPORT, ESCAPE_DESCRIPTOR);
                    if (invocation == null || !invocation.completed("jvm",
                            "the escaping async throw operand")) {
                        break;
                    }
                    if (invocation.result()
                            instanceof JvmAsyncExportInvoker.Result.DealError error) {
                        assertEscapedTuple("jvm", error.code(), error.message(),
                            error.file(), error.line(), error.column(),
                            error.expected(), error.actual(), error.frames(), read,
                            project);
                    } else {
                        fail("jvm: the escaping async operand produced the DEAL "
                            + "error variant; got " + invocation.result());
                    }
                }
                case JS -> {
                    Files.writeString(project.out().resolve("probe.js"),
                        jsDriver(ESCAPE_EXPORT, ESCAPE_DESCRIPTOR),
                        StandardCharsets.UTF_8);
                    ProcessOutcome run = runProcess(List.of("node", "probe.js"),
                        project.out());
                    checkEq(0, run.exitCode(), "js: the invoker probe runs: "
                        + run.output());
                    String[] fields = run.stdout().strip().split("\\|", -1);
                    if (fields.length == 8 && fields[0].equals("ERR")) {
                        assertEscapedTuple("js", fields[1], fields[2], fields[3],
                            integerOrNull(fields[4]), integerOrNull(fields[5]),
                            fields[6], fields[7], null, read, project);
                    } else {
                        fail("js: the escaping async operand produced the "
                            + "invoker's reified DEAL error variant; got "
                            + run.output());
                    }
                }
                default -> throw new IllegalStateException(
                    "no async invoker lane for " + backend);
            }
            deleteRecursively(project.root());
        }
    }

    /** One invoker lane's numeric field, absent for an unset or non-numeric one. */
    private static Integer integerOrNull(String text) {
        if (text == null || text.isEmpty() || text.equals("null")
                || text.equals("undefined")) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException absent) {
            return null;
        }
    }

    /**
     * The escaped async operand's complete tuple: the canonical typed-boundary
     * code/message and expected/actual pair, the read's own source range, and
     * the file the fixture was materialized from — an infrastructure or host
     * failure carries none of these and is a different result variant.
     */
    private static void assertEscapedTuple(String backend, String code,
            String message, String file, Integer line, Integer column,
            String expected, String actual, String frames, Position read,
            Project project) {
        checkEq(EXPECTED_CODE, code, backend + ": the escaped async throw keeps "
            + "the canonical typed-boundary code");
        checkEq(EXPECTED_MESSAGE, message, backend + ": the escaped async throw "
            + "keeps the canonical typed-boundary message");
        checkEq(read.line(), line, backend + ": the failure is reported at the "
            + "operand read's own line");
        checkEq(read.column(), column, backend + ": the failure is reported at "
            + "the operand read's own column");
        checkEq(EXPECTED_TYPE, expected, backend + ": the expected projection is "
            + "the refined descriptor's kind token");
        checkEq(ACTUAL_KIND, actual, backend + ": the actual projection is the "
            + "cleared value's kind");
        check(file != null && Path.of(file).toAbsolutePath().normalize()
                .equals(project.entry().toAbsolutePath().normalize()),
            backend + ": the failure keeps the fixture's own source file; got "
                + file);
        if (frames != null) {
            check(frames.contains(ESCAPE_EXPORT), backend + ": the failure keeps "
                + "the async export's own frame; got " + frames);
        }
    }

    // =========================================================================
    // The production invoker lanes (an infrastructure failure is thrown by the
    // invoker, so it is captured and reported by name)
    // =========================================================================

    /** One invoker lane's outcome, or the exception the invoker raised. */
    private record Invocation(Object result, RuntimeException infrastructure) {

        /** Reports a thrown infrastructure failure and whether a result exists. */
        boolean completed(String backend, String what) {
            if (infrastructure == null) {
                return true;
            }
            fail(backend + ": " + what + " reached the production invoker as an "
                + "infrastructure failure instead of a DEAL outcome: "
                + infrastructure);
            return false;
        }
    }

    private static Invocation invokeQuietly(Supplier<Object> lane) {
        try {
            return new Invocation(lane.get(), null);
        } catch (RuntimeException infrastructure) {
            return new Invocation(null, infrastructure);
        }
    }

    private static Invocation invokeLuaJit(Project project, String export,
            String descriptor) {
        return invokeQuietly(() -> new LuaJitAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(project.out().resolve("main.lua"),
                export, descriptor)));
    }

    /**
     * The JVM lane: the emitted artifact compiles under
     * {@code javac --release 25 -proc:none}, then one production invocation
     * of the export. A rejected artifact compiles to {@code null} after the
     * named failure is reported.
     */
    private static Invocation invokeJvm(Project project, String export,
            String descriptor) throws Exception {
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
            return null;
        }
        return invokeQuietly(() -> new JvmAsyncExportInvoker().invoke(
            new AsyncExportInvocationRequest(classes.resolve("Main.class"),
                export, descriptor)));
    }

    // =========================================================================
    // Regression 2: the caught async operand completes on the invoker surface
    // =========================================================================

    private static void testCaughtAsyncInvoker(Path root) throws Exception {
        System.out.println("-- the caught async throw operand completes on the "
            + "production async invokers (never an infrastructure failure) --");
        String source = fixtureSource(ASYNC_CAUGHT);
        for (Backend backend : List.of(Backend.LUAJIT, Backend.JVM, Backend.JS)) {
            Project project = writeProject(
                root.resolve(backend.name().toLowerCase()), source);
            productionCompile(project, backend);
            switch (backend) {
                case LUAJIT -> {
                    Invocation invocation = invokeLuaJit(project, CAUGHT_EXPORT,
                        CAUGHT_DESCRIPTOR);
                    if (invocation.completed("luajit", "the caught async "
                            + "throw operand")) {
                        assertCaughtCompletion("luajit", invocation.result());
                    }
                }
                case JVM -> {
                    Invocation invocation = invokeJvm(project, CAUGHT_EXPORT, CAUGHT_DESCRIPTOR);
                    if (invocation != null && invocation.completed("jvm", "the "
                            + "caught async throw operand")) {
                        assertCaughtCompletion("jvm", invocation.result());
                    }
                }
                case JS -> {
                    Files.writeString(project.out().resolve("probe.js"),
                        jsDriver(CAUGHT_EXPORT, CAUGHT_DESCRIPTOR),
                        StandardCharsets.UTF_8);
                    ProcessOutcome run = runProcess(List.of("node", "probe.js"),
                        project.out());
                    checkEq(0, run.exitCode(), "js: the invoker probe runs: "
                        + run.output());
                    checkEq("OK|1", run.stdout().strip(), "js: the async caught "
                        + "control completes with its own value instead of an "
                        + "error or infrastructure failure: " + run.output());
                    checkEq("", run.stderr(), "js: no diagnostics on stderr: "
                        + run.output());
                }
                default -> throw new IllegalStateException(
                    "no async invoker lane for " + backend);
            }
            deleteRecursively(project.root());
        }
    }

    private static void assertCaughtCompletion(String backend, Object result) {
        switch (result) {
            case LuaJitAsyncExportInvoker.Result.Value value -> {
                checkEq(CAUGHT_DESCRIPTOR, value.returnDescriptor(), backend
                    + ": the caught async control's completion descriptor");
                checkEq(1.0, Double.parseDouble(value.valueJson()), backend + ": the caught async "
                    + "control completes with its own value");
            }
            case LuaJitAsyncExportInvoker.Result.DealError error -> fail(backend
                + ": the caught async control escaped a DEAL failure: " + error);
            case LuaJitAsyncExportInvoker.Result.HostFailure failure -> fail(backend
                + ": the caught async control was an infrastructure failure: "
                + failure);
            case JvmAsyncExportInvoker.Result.Value value -> {
                checkEq(CAUGHT_DESCRIPTOR, value.returnDescriptor(), backend
                    + ": the caught async control's completion descriptor");
                checkEq(1.0, Double.parseDouble(value.valueJson()), backend + ": the caught async "
                    + "control completes with its own value");
            }
            case JvmAsyncExportInvoker.Result.DealError error -> fail(backend
                + ": the caught async control escaped a DEAL failure: " + error);
            case JvmAsyncExportInvoker.Result.HostFailure failure -> fail(backend
                + ": the caught async control was an infrastructure failure: "
                + failure);
            default -> fail(backend + ": unexpected production invoker result "
                + result);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Narrowed Throw Admission Production Test "
            + "(ISSUE-0887) ===\n");
        check(fixtureSource(ASYNC_ESCAPE).contains("throw e;"), "the escaping "
            + "fixture carries the cleared-binding throw operand");
        check(fixtureSource(ASYNC_CAUGHT).contains("catch (caught)"),
            "the controls fixture carries the catch arm of the async operand");
        Path root = Files.createTempDirectory("deal-narrowed-throw-");
        try {
            testEscapingAsyncInvoker(root.resolve("escape"));
            testCaughtAsyncInvoker(root.resolve("caught"));
        } finally {
            deleteRecursively(root);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.out.println("=== Narrowed Throw Admission Production Tests "
                + "FAILED ===");
            System.exit(1);
        }
        System.out.println("=== Narrowed Throw Admission Production Tests "
            + "Passed ===");
    }
}
