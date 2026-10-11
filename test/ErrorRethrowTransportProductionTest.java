package deal.test;

import deal.checker.BuiltinErrorDeclaration;
import deal.module.CompilationOrchestrator;
import deal.semantic.CheckedProjectBuildResult;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.RequirementManifestResult;
import deal.semantic.SemanticLowerer;
import deal.semantic.SemanticRuntimeModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ISSUE-0751 review cycle 1, finding 2 regression: an earlier caught error
 * rethrown through a handler for a different error.
 *
 * <p>The companion raises an original failure that already carries a
 * cause (a new failure raised while handling the caught E8001 links it),
 * {@code relay} rethrows an earlier caught error through a catch handling
 * a different error, and the entry's {@code main} drives the shape. The
 * rethrow must propagate the original failure unchanged — code, message,
 * origin, expected/actual, frames and its own cause preserved — and must
 * never acquire the relay's handled failure as a cause. The regression
 * runs all three consumers of the one lowering (the semantic oracle and
 * the two shared artifacts) and compares the complete nested error
 * snapshot of each terminal.</p>
 */
public class ErrorRethrowTransportProductionTest {

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

    private static void fail(String message) {
        failed++;
        System.err.println("FAIL: " + message);
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    /**
     * The companion: {@code inner} raises the typed E8001 with the
     * expected/actual pair and the raising origin, {@code withCause}
     * re-raises it as the replacement WRAPPED failure that links it as
     * its cause (the original error with an existing cause), and
     * {@code probeCause} rethrows the caught WRAPPED error through the
     * {@code relay} handler for the unrelated B failure.
     */
    private static final String LIB_SOURCE = """
        function inner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export function withCause(): int {
          try { return inner(); }
          catch (e) { throw { code: "WRAPPED", message: "wrapped" }; }
        }

        export function relay(original: Error): int {
          try { throw { code: "B", message: "second" }; }
          catch (other) { throw original; }
        }

        export function probeCause(): int {
          try { return withCause(); }
          catch (original) { return relay(original); }
        }
        """;

    /** The entry drives the companion probe through the module boundary. */
    private static final String APP_SOURCE = """
        import * as helper from "./lib";

        export function main(): null {
          let x: int = helper.probeCause();
          return null;
        }
        """;

    private record Fixture(Path root, CheckedProjectBuildResult built,
                           RequirementManifestResult manifests,
                           CompilationOrchestrator orchestrator) {
    }

    private static CompilerInvocation invocation() {
        return CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
    }

    private static Fixture compileProject() throws Exception {
        Path root = Files.createTempDirectory("error-rethrow-project");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("lib.deal"), LIB_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("app.deal"), APP_SOURCE, StandardCharsets.UTF_8);
        Path entry = src.resolve("app.deal").toAbsolutePath();
        Path output = root.resolve("out").toAbsolutePath();
        CompilationOrchestrator orchestrator = new CompilationOrchestrator(entry, output,
            false, null, List.of(src.toAbsolutePath()), null);
        boolean compiled = orchestrator.compile();
        CheckedProjectBuildResult built = orchestrator.checkedProject();
        RequirementManifestResult manifests = orchestrator.requirementManifests();
        if (!compiled || built == null || built.input() == null || built.index() == null
                || built.hasErrors() || manifests == null || manifests.manifests() == null) {
            String detail = built == null ? "no checked project"
                : String.valueOf(built.diagnostics());
            deleteRecursively(root);
            throw new IllegalStateException("the fixture project did not build: " + detail
                + " / " + orchestrator.diagnostics());
        }
        return new Fixture(root, built, manifests, orchestrator);
    }

    private static SemanticLowerer.ProjectLoweringResult lower(Fixture fixture) {
        return SemanticLowerer.lowerProject(invocation(), fixture.built().input(),
            fixture.built().index(), fixture.manifests().manifests(),
            fixture.orchestrator().hostDeclarationSurface(),
            new LinkedHashMap<>(), new LinkedHashMap<>(),
            BuiltinErrorDeclaration.synthesized(
                fixture.built().input().modules().get(0).ast().span()),
            List.of(deal.semantic.ir.IntrinsicKind.INT_CONVERT,
                deal.semantic.ir.IntrinsicKind.NUMBER_CONVERT,
                deal.semantic.ir.IntrinsicKind.BYTES_NEW),
            Set.of());
    }

    /**
     * The three-consumer regression: the one lowering's terminal on the
     * semantic oracle and both shared artifacts is the WRAPPED failure
     * with its own E8001 cause and no invented wrapper — the rethrow of
     * the earlier error through the relay's unrelated handler preserves
     * the transported failure and its cause on every consumer.
     */
    private static void testRethrowAcrossHandler() throws Exception {
        System.out.println("-- an earlier error rethrown through a handler for "
            + "a different error keeps its own cause on all three consumers --");
        Fixture fixture = compileProject();
        Path workspace = Files.createTempDirectory("error-rethrow-matrix");
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            check(result.project() != null && result.diagnostics().isEmpty(),
                "the fixture lowers with zero diagnostics: " + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            SemanticDifferentialHarness.Expectation expectation =
                SemanticDifferentialHarness.Expectation.failure(
                    "an earlier error rethrown through a handler for a different error",
                    List.of(), "WRAPPED", null);
            SemanticDifferentialHarness.Verdict verdict =
                SemanticDifferentialHarness.runProject(result.project(), result.tables(),
                    result.registries(), expectation, workspace);
            check(verdict.pass(), "the oracle and both shared artifacts agree "
                + "event-for-event with the pinned terminal: " + verdict.failures());
            checkEq(3, verdict.runs().size(),
                "the differential matrix produced the three consumers: "
                    + verdict.failures());
            String snapshot = null;
            for (SemanticRuntimeModel.ConsumerRun run : verdict.runs()) {
                if (!(run.terminal()
                        instanceof SemanticRuntimeModel.Terminal.DealFailure terminal)) {
                    fail(run.consumer() + ": the terminal is the escaped DEAL failure; got "
                        + run.terminal());
                    continue;
                }
                SemanticRuntimeModel.ErrorSnapshot error = terminal.error();
                checkEq("WRAPPED", error.code(), run.consumer()
                    + ": the rethrown failure keeps its code");
                checkEq("wrapped", error.message(), run.consumer()
                    + ": the rethrown failure keeps its message");
                check(error.origin() != null
                        && error.origin().endsWith("src/lib.deal:9:15"),
                    run.consumer() + ": the rethrown failure keeps the original "
                        + "source location of the replacement failure; got "
                        + error.origin());
                checkEq(null, error.expected(), run.consumer()
                    + ": the rethrown failure keeps the absent expected type");
                checkEq(null, error.actual(), run.consumer()
                    + ": the rethrown failure keeps the absent actual kind");
                check(String.join(",", error.frames()).matches(
                        "lib#withCause@F\\d+,lib#probeCause@F\\d+,app#main@F\\d+"),
                    run.consumer() + ": the rethrown failure keeps the original "
                        + "frames: " + error.frames());
                SemanticRuntimeModel.ErrorSnapshot cause = error.cause();
                check(cause != null, run.consumer() + ": the rethrown failure keeps "
                    + "its own cause");
                if (cause == null) {
                    continue;
                }
                checkEq("E8001", cause.code(), run.consumer()
                    + ": the original cause is the E8001, never the relay's handled "
                        + "B failure");
                checkEq("expected int", cause.message(), run.consumer()
                    + ": the original cause keeps its message");
                check(cause.origin() != null
                        && cause.origin().endsWith("src/lib.deal:3:16"),
                    run.consumer() + ": the original cause keeps its source "
                        + "location; got " + cause.origin());
                checkEq("int", cause.expected(), run.consumer()
                    + ": the original cause keeps its expected type");
                checkEq("string", cause.actual(), run.consumer()
                    + ": the original cause keeps its actual kind");
                check(String.join(",", cause.frames()).matches(
                        "lib#inner@F\\d+,lib#withCause@F\\d+,lib#probeCause@F\\d+,"
                            + "app#main@F\\d+"),
                    run.consumer() + ": the original cause keeps its frames: "
                        + cause.frames());
                checkEq(null, cause.cause(), run.consumer()
                    + ": the original cause never gains an invented cause");
                if (snapshot == null) {
                    snapshot = error.text();
                } else {
                    checkEq(snapshot, error.text(), run.consumer()
                        + ": the complete nested terminal snapshot equals the first "
                            + "consumer's");
                }
            }
        } finally {
            deleteRecursively(workspace);
            deleteRecursively(fixture.root());
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup.
                }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        testRethrowAcrossHandler();
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.out.println("=== Error Rethrow Transport Production Tests "
                + "FAILED ===");
            System.exit(1);
        }
        System.out.println("=== Error Rethrow Transport Production Tests Passed ===");
    }
}
