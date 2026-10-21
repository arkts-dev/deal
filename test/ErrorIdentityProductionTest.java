package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.codegen.Backend;
import deal.codegen.jvm.JvmAsyncExportInvoker;
import deal.codegen.jvm.JvmBackend;
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
 *   <li>the emitted artifacts of all three backends (real {@code luajit},
 *       {@code java}, {@code node}) executing the identity, alias-mutation,
 *       rethrow, nested-catch, parameter/return-crossing postconditions,
 *       asserted from the exact stdout markers and exit code;</li>
 *   <li>the canonical failure metadata of the identity-preserving paths on
 *       the semantic oracle and both shared artifacts: a mutated first-throw
 *       rethrow keeps the FIRST throw's origin with no invented cause, and a
 *       handler's replacement keeps its own origin and links the caught
 *       failure as its cause;</li>
 *   <li>the awaited error propagation through the production async-export
 *       invokers on LuaJIT and JVM and through the published JS artifact
 *       under {@code node}.</li>
 * </ul>
 */
public final class ErrorIdentityProductionTest {

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
    // The fixtures
    // =========================================================================

    /**
     * The sync identity fixture of the emitted-artifact regression: every
     * postcondition is asserted in DEAL source, so a backend that loses the
     * thrown instance raises {@code TEST_FAIL} instead of reaching the
     * {@code OK} markers. Two aliases, the first throw, the mutation through
     * either alias, a rethrow from a callee's handler, a handler's
     * replacement caught by the enclosing catch, and a parameter/return
     * crossing are covered.
     */
    private static final String IDENTITY_SOURCE = """
        import * as console from "std/console";

        function mutateReturned(e: Error): Error {
          e.message = "returned";
          return e;
        }

        function rethrowCaught(e: Error): int {
          try { throw e; } catch (caught) { throw caught; }
          return 0;
        }

        export function main(): null {
          let original: Error = { code: "X", message: "x" };
          let alias: Error = original;
          let caughtOnce: boolean = false;
          try {
            throw original;
          } catch (e) {
            caughtOnce = true;
            if (e !== original || e !== alias) {
              throw { code: "TEST_FAIL", message: "the first throw lost the identity" };
            }
            e.message = "changed";
            if (original.message !== "changed" || alias.message !== "changed") {
              throw { code: "TEST_FAIL", message: "the caught instance is not alias-visible" };
            }
          }
          if (!caughtOnce) {
            throw { code: "TEST_FAIL", message: "the first throw was not caught" };
          }
          if (original.message !== "changed" || alias.message !== "changed") {
            throw { code: "TEST_FAIL", message: "the alias write is not visible after the catch" };
          }
          console.log("OK|first-throw");

          let crossed: Error = { code: "R", message: "r" };
          let again: boolean = false;
          try {
            let x: int = rethrowCaught(crossed);
          } catch (recaught) {
            again = true;
            if (recaught !== crossed) {
              throw { code: "TEST_FAIL", message: "the rethrow lost the identity" };
            }
          }
          if (!again) {
            throw { code: "TEST_FAIL", message: "the rethrow was not caught" };
          }
          console.log("OK|rethrow");

          let outer: Error = { code: "OUTER", message: "outer" };
          let handled: boolean = false;
          try {
            try {
              throw { code: "INNER", message: "inner" };
            } catch (inner) {
              if (inner.code !== "INNER") {
                throw { code: "TEST_FAIL", message: "the handled Error lost its code" };
              }
              throw outer;
            }
          } catch (o) {
            handled = true;
            if (o !== outer) {
              throw { code: "TEST_FAIL", message: "the handler replacement lost the identity" };
            }
            o.code = "OUTER2";
          }
          if (!handled) {
            throw { code: "TEST_FAIL", message: "the handler replacement was not caught" };
          }
          if (outer.code !== "OUTER2") {
            throw { code: "TEST_FAIL", message: "the enclosing catch did not bind the instance" };
          }
          console.log("OK|nested-catch");

          let passedOn: Error = { code: "P", message: "p" };
          let returned: Error = mutateReturned(passedOn);
          if (returned !== passedOn) {
            throw { code: "TEST_FAIL", message: "the returned Error is a replacement" };
          }
          if (passedOn.message !== "returned") {
            throw { code: "TEST_FAIL", message: "the callee write is not alias-visible" };
          }
          console.log("OK|crossing");

          return null;
        }
        """;

    /** The exact stdout of the identity fixture on every backend. */
    private static final String IDENTITY_STDOUT =
        "OK|first-throw\nOK|rethrow\nOK|nested-catch\nOK|crossing\n";

    /** The awaited-propagation fixture (one zero-arity async export). */
    private static final String AWAITED_SOURCE = """
        export async function probe(): string {
          let original: Error = { code: "A", message: "a" };
          let alias: Error = original;

          async function asyncThrow(): int {
            throw original;
          }

          let caught: boolean = false;
          try {
            let x: int = await asyncThrow();
          } catch (e) {
            caught = true;
            if (e !== original || e !== alias) {
              throw { code: "TEST_FAIL", message: "the awaited error lost the identity" };
            }
            e.code = "A2";
            if (original.code !== "A2" || alias.code !== "A2") {
              throw { code: "TEST_FAIL", message: "the awaited instance is not alias-visible" };
            }
          }
          if (!caught) {
            throw { code: "TEST_FAIL", message: "the awaited error was not caught" };
          }
          return "awaited";
        }

        export function main(): null {
          return null;
        }
        """;

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

    // =========================================================================
    // Regression 1: the identity postconditions on the three emitted artifacts
    // =========================================================================

    private static void assertArtifactRun(String backend, Path out,
            List<String> command, Path workDir) throws Exception {
        ProcessOutcome run = runProcess(command, workDir);
        checkEq(0, run.exitCode(), backend + ": the published artifact keeps the "
            + "Error identity and exits zero: " + run.output());
        checkEq(IDENTITY_STDOUT, run.stdout(), backend + ": the identity "
            + "postcondition markers: " + run.output());
        checkEq("", run.stderr(), backend + ": no diagnostics on stderr: "
            + run.output());
    }

    private static void testLuaJitIdentityArtifact(Path root) throws Exception {
        System.out.println("-- the published LuaJIT chunk: identity postconditions"
            + " under real luajit --");
        Project project = writeProject(root, IDENTITY_SOURCE);
        productionCompile(project, Backend.LUAJIT);
        assertArtifactRun("luajit", project.out(),
            List.of("luajit", "main.lua"), project.out());
    }

    private static void testJvmIdentityArtifact(Path root) throws Exception {
        System.out.println("-- the published JVM artifact: identity postconditions"
            + " under javac and java --");
        Project project = writeProject(root, IDENTITY_SOURCE);
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
        assertArtifactRun("jvm", classes, List.of("java", "-cp",
            classes + File.pathSeparator + classpath, "Main"), project.out());
    }

    private static void testJsIdentityArtifact(Path root) throws Exception {
        System.out.println("-- the published JS artifact: identity postconditions"
            + " under node --");
        Project project = writeProject(root, IDENTITY_SOURCE);
        productionCompile(project, Backend.JS);
        assertArtifactRun("js", project.out(),
            List.of("node", "main.js"), project.out());
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
            Project project = writeProject(projectRoot, AWAITED_SOURCE);
            productionCompile(project, backend);
            switch (backend) {
                case LUAJIT -> {
                    Object result = new LuaJitAsyncExportInvoker().invoke(
                        new AsyncExportInvocationRequest(
                            project.out().resolve("main.lua"), "probe", "string"));
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
                            classes.resolve("Main.class"), "probe", "string"));
                    assertAwaitCompletion(backend.name(), result);
                }
                case JS -> {
                    writeFile(project.out(), "probe.js", JS_ASYNC_DRIVER);
                    ProcessOutcome run = runProcess(List.of("node", "probe.js"),
                        project.out());
                    checkEq(0, run.exitCode(), "js: the awaited error keeps the "
                        + "Error instance: " + run.output());
                    checkEq("OK|awaited|awaited\n", run.stdout(),
                        "js: the awaited-error postcondition marker: " + run.output());
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
        $rt.invokeAsyncExport($entry, "probe", "string").then(($result) => {
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
                checkEq("string", value.returnDescriptor(), backend
                    + ": the awaited completion descriptor");
                checkEq("\"awaited\"", value.valueJson(), backend
                    + ": the awaited error kept the Error instance (the probe's "
                    + "postconditions passed)");
            }
            case LuaJitAsyncExportInvoker.Result.DealError error -> fail(backend
                + ": the awaited probe escaped a DEAL failure instead of completing: "
                + error);
            case LuaJitAsyncExportInvoker.Result.HostFailure failure -> fail(backend
                + ": the awaited probe was a host failure: " + failure);
            case JvmAsyncExportInvoker.Result.Value value -> {
                checkEq("string", value.returnDescriptor(), backend
                    + ": the awaited completion descriptor");
                checkEq("\"awaited\"", value.valueJson(), backend
                    + ": the awaited error kept the Error instance (the probe's "
                    + "postconditions passed)");
            }
            case JvmAsyncExportInvoker.Result.DealError error -> fail(backend
                + ": the awaited probe escaped a DEAL failure instead of completing: "
                + error);
            case JvmAsyncExportInvoker.Result.HostFailure failure -> fail(backend
                + ": the awaited probe was a host failure: " + failure);
            default -> fail(backend + ": unexpected production invoker result "
                + result);
        }
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Error Identity Production Test (ISSUE-0881) ===\n");
        Path root = Files.createTempDirectory("deal-error-identity-");
        try {
            testLuaJitIdentityArtifact(root.resolve("luajit"));
            testJvmIdentityArtifact(root.resolve("jvm"));
            testJsIdentityArtifact(root.resolve("js"));
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
