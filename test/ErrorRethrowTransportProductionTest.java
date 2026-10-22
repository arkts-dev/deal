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

    /**
     * The saved-cause-alias fixture: {@code inner} raises the typed E8001,
     * {@code raiseOuter} catches it as {@code e}, retains the alias in
     * {@code saved}, and raises the fresh constructed OUTER failure (which
     * links the E8001 as its cause); {@code probe} catches OUTER, edits the
     * retained E8001 alias's code and message, and rethrows OUTER. The
     * linked cause must keep the transport captured at catch time on every
     * consumer — never the later alias edit.
     */
    private static final String SAVED_CAUSE_LIB_SOURCE = """
        function inner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export function probe(): int {
          let saved: Error = { code: "seed", message: "seed" };

          function raiseOuter(): int {
            try { return inner(); }
            catch (e) {
              saved = e;
              throw { code: "OUTER", message: "outer" };
            }
          }

          try { return raiseOuter(); }
          catch (o) {
            saved.code = "CHANGED";
            saved.message = "changed";
            throw o;
          }
        }
        """;

    /** The entry drives the saved-cause-alias probe through the module boundary. */
    private static final String SAVED_CAUSE_APP_SOURCE = """
        import * as helper from "./lib";

        export function main(): null {
          let x: int = helper.probe();
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
        return compileProject(LIB_SOURCE, APP_SOURCE);
    }

    private static Fixture compileProject(String libSource, String appSource)
            throws Exception {
        Path root = Files.createTempDirectory("error-rethrow-project");
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("lib.deal"), libSource, StandardCharsets.UTF_8);
        Files.writeString(src.resolve("app.deal"), appSource, StandardCharsets.UTF_8);
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
            new LinkedHashMap<>(), BuiltinErrorDeclaration.synthesized(
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

    /**
     * The saved-cause-alias regression: the retained E8001 alias is edited
     * after the OUTER replacement linked it as cause, and OUTER is
     * rethrown. The linked cause is the stable transport captured at catch
     * time on all three consumers — its code, message, origin,
     * expected/actual and frames never change retroactively — while the
     * rethrow publishes the OUTER identity's own code/message.
     */
    private static void testSavedCauseAliasMutation() throws Exception {
        System.out.println("-- an edited Error alias never rewrites an already "
            + "linked cause on any consumer --");
        Fixture fixture = compileProject(SAVED_CAUSE_LIB_SOURCE,
            SAVED_CAUSE_APP_SOURCE);
        Path workspace = Files.createTempDirectory("error-saved-cause-matrix");
        try {
            SemanticLowerer.ProjectLoweringResult result = lower(fixture);
            check(result.project() != null && result.diagnostics().isEmpty(),
                "the saved-cause fixture lowers with zero diagnostics: "
                    + result.diagnostics());
            if (result.project() == null) {
                return;
            }
            SemanticDifferentialHarness.Expectation expectation =
                SemanticDifferentialHarness.Expectation.failure(
                    "an edited Error alias never rewrites a linked cause",
                    List.of(), "OUTER", null);
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
                checkEq("OUTER", error.code(), run.consumer()
                    + ": the rethrown failure keeps the OUTER code");
                checkEq("outer", error.message(), run.consumer()
                    + ": the rethrown failure keeps the OUTER message");
                check(error.origin() != null
                        && error.origin().contains("src/lib.deal:14:"),
                    run.consumer() + ": the constructed OUTER raises at its own "
                        + "THROW origin; got " + error.origin());
                checkEq(null, error.expected(), run.consumer()
                    + ": the rethrown failure keeps the absent expected type");
                checkEq(null, error.actual(), run.consumer()
                    + ": the rethrown failure keeps the absent actual kind");
                check(String.join(",", error.frames()).matches(
                        "lib#raiseOuter@F\\d+,lib#probe@F\\d+,app#main@F\\d+"),
                    run.consumer() + ": the rethrown failure keeps the frames "
                        + "captured at the raise: " + error.frames());
                SemanticRuntimeModel.ErrorSnapshot cause = error.cause();
                check(cause != null, run.consumer() + ": the rethrown failure keeps "
                    + "its own cause");
                if (cause == null) {
                    continue;
                }
                checkEq("E8001", cause.code(), run.consumer()
                    + ": the linked cause stays the E8001, never the edited "
                    + "CHANGED alias code");
                checkEq("expected int", cause.message(), run.consumer()
                    + ": the linked cause keeps its captured message, never the "
                    + "edited one");
                check(cause.origin() != null
                        && cause.origin().endsWith("src/lib.deal:3:16"),
                    run.consumer() + ": the linked cause keeps its captured "
                        + "source location; got " + cause.origin());
                checkEq("int", cause.expected(), run.consumer()
                    + ": the linked cause keeps its captured expected type");
                checkEq("string", cause.actual(), run.consumer()
                    + ": the linked cause keeps its captured actual kind");
                check(String.join(",", cause.frames()).matches(
                        "lib#inner@F\\d+,lib#raiseOuter@F\\d+,lib#probe@F\\d+,"
                            + "app#main@F\\d+"),
                    run.consumer() + ": the linked cause keeps its captured "
                        + "frames: " + cause.frames());
                checkEq(null, cause.cause(), run.consumer()
                    + ": the linked cause gains no invented cause");
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

    static final String HOST_ERROR_LIB_SOURCE = """
        function inner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export function fail(): int {
          return inner();
        }

        async function ainner(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }

        export async function afail(): int {
          return await ainner();
        }

        export function boom(): int {
          return 2147483647 + 1;
        }

        export function echo(e: Error): int { throw e; }
        export function alias(): boolean {
          let first: Error = { code: "seed", message: "seed" };
          try { throw { code: "X", message: "x" }; } catch (caught) { first = caught; }
          first.code = "Z";
          let again: Error = { code: "seed", message: "seed" };
          try { throw first; } catch (caught) { again = caught; }
          return first === again && again.code === "Z";
        }
        """;

    static final String HOST_ERROR_APP_SOURCE = """
        import * as helper from "./lib";
        export function main(): null { return null; }
        export function run(): int { return helper.fail(); }
        export function reraise(): int {
          try { return helper.fail(); } catch (e) { throw e; }
        }
        export function leafFail(): int {
          let t: table = { value: "bad" };
          let x: int = t.value;
          return x;
        }
        export function recur(n: int, step: (n: int) => int): int {
          if (n === 0) {
            let t: table = { value: "bad" };
            let x: int = t.value;
            return x;
          }
          return step(n - 1);
        }
        export function swallow(): int {
          try { return helper.fail(); } catch (e) { return 0; }
        }
        export async function arun(): int { return await helper.afail(); }
        export async function areraise(): int {
          try { return await helper.afail(); } catch (e) { throw e; }
        }
        export function overflow(): int { return helper.boom(); }
        export function replaced(): int {
          try { return helper.fail(); }
          catch (e) { throw { code: "REPLACED", message: "handled" }; }
        }
        export function echo(e: Error): int { return helper.echo(e); }
        export function alias(): boolean { return helper.alias(); }
        export function mutate(): int {
          let saved: Error = { code: "seed", message: "seed" };
          function step(): int {
            try {
              try { return helper.fail(); }
              catch (e) { saved = e; throw { code: "OUTER", message: "outer" }; }
            } catch (o) {
              saved.code = "CHANGED";
              saved.message = "changed";
              throw o;
            }
          }
          return step();
        }
        """;

    static void assertProductionHostTransport(String stdout, String stderr, boolean jvm) {
        checkEq("", stderr, "production host probe has no stderr");
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (String line : stdout.split("\n", -1)) {
            if (line.startsWith("HOST-ERR|")) {
                String[] fields = line.split("\\|", -1);
                if (fields.length == 4 && fields[1].equals("swallow")) {
                    checkEq("HOST-ERR|swallow|none|0", line, "handling export returns zero");
                } else {
                    checkEq(9, fields.length, "closed host error row: " + line);
                    if (fields.length == 9) {
                        check(rows.put(fields[1], fields) == null, "unique host row " + fields[1]);
                    }
                }
            }
        }
        checkEq(Set.of("run", "reraise", "leafFail", "arun", "areraise", "overflow", "replaced", "mutate"),
            rows.keySet(), "complete production host error rows");
        hostFields(stdout, "HOST-ERR|swallow|", 2);
        for (String name : List.of("run", "reraise", "leafFail", "arun", "areraise", "overflow", "replaced")) {
            String[] row = rows.get(name);
            check(row != null, "host error " + name + " exists");
            if (row == null) {
                continue;
            }
            boolean overflow = name.equals("overflow");
            boolean replaced = name.equals("replaced");
            boolean async = name.startsWith("a");
            boolean appOrigin = name.equals("leafFail") || replaced;
            checkEq(overflow ? "E8004" : replaced ? "REPLACED" : "E8001", row[2], name + " code");
            checkEq(overflow ? "int out of safe range" : replaced ? "handled" : "expected int",
                row[3], name + " message");
            check(appOrigin ? row[4].contains("src/app.deal:")
                : row[4].endsWith("src/lib.deal:" + (overflow ? "22:10" : async ? "13:16" : "3:16")),
                name + " raising origin: " + row[4]);
            checkEq(overflow || replaced ? "-" : "int", row[5], name + " expected");
            checkEq(overflow || replaced ? "-" : "string", row[6], name + " actual");
            String frames = appOrigin ? "" : overflow ? "lib#boom@F\\d+,"
                : async ? "lib#ainner@F\\d+,lib#afail@F\\d+," : "lib#inner@F\\d+,lib#fail@F\\d+,";
            check(row[7].matches(frames + "app#" + name + "@F\\d+"), name + " named frames: " + row[7]);
            checkEq(replaced ? "E8001" : "-", row[8], name + " applicable cause");
        }
        for (List<String> pair : List.of(List.of("run", "reraise"), List.of("arun", "areraise"))) {
            String[] direct = rows.get(pair.get(0));
            String[] rethrow = rows.get(pair.get(1));
            if (direct != null && rethrow != null) {
                for (int field : List.of(2, 3, 4, 5, 6, 8)) {
                    checkEq(direct[field], rethrow[field], pair + " preserves field " + field);
                }
            }
        }
        String[] cause = hostFields(stdout, "HOST-CAUSE|mutate|", 9);
        if (cause != null) {
            checkEq(List.of("OUTER", "outer", "E8001", "expected int"),
                List.of(cause[0], cause[1], cause[2], cause[3]), "saved cause keeps captured values");
            check(cause[4].endsWith("src/lib.deal:3:16"), "saved cause keeps raising origin");
            checkEq("int", cause[5], "saved cause expected");
            checkEq("string", cause[6], "saved cause actual");
            check(cause[7].matches("lib#inner@F\\d+,lib#fail@F\\d+,app#step@F\\d+,app#mutate@F\\d+"),
                "saved cause keeps captured frames: " + cause[7]);
            checkEq("-", cause[8], "saved cause gains no cause");
        }
        String[] reentry = hostFields(stdout, "HOST-REENTRY|recur|", 7);
        if (reentry != null) {
            checkEq("E8001", reentry[0], "reentry code");
            checkEq("expected int", reentry[1], "reentry message");
            check(reentry[2].contains("src/app.deal:"), "reentry raising origin");
            checkEq("int", reentry[3], "reentry expected");
            checkEq("string", reentry[4], "reentry actual");
            check(reentry[5].matches("app#recur@F\\d+,app#recur@F\\d+,app#recur@F\\d+"),
                "three live reentry frames: " + reentry[5]);
            String[] frames = reentry[5].split(",");
            check(frames.length == 3 && frames[0].equals(frames[1]) && frames[1].equals(frames[2]),
                "reentry frames have identical labels without deduplication");
            checkEq("-", reentry[6], "reentry invents no cause");
        }
        String[] empty = hostFields(stdout, "HOST-EMPTY|recur|", 1);
        if (empty != null) {
            checkEq("-", empty[0], "reentry leaves no active frame");
        }
        String[] clean = hostFields(stdout, "HOST-CLEAN|leafFail|", 1);
        if (clean != null) {
            check(clean[0].matches("app#leafFail@F\\d+"), "cleanup after failure and success");
        }
        String[] alias = hostFields(stdout, "HOST-ID|alias|", 1);
        if (alias != null) {
            checkEq("true", alias[0], "catch/rethrow alias preserves Error identity");
        }
        if (jvm) {
            String[] echo = hostFields(stdout, "HOST-ID|echo|", 5);
            if (echo != null) {
                checkEq("true", echo[0], "unchanged rethrow preserves DealError identity");
                checkEq("true", echo[1], "unchanged rethrow preserves reified Error identity");
                checkEq("E8001", echo[2], "identity echo code");
                check(echo[3].endsWith("src/lib.deal:3:16"), "identity echo origin");
                checkEq("-", echo[4], "identity echo gains no cause");
            }
        }
    }

    private static String[] hostFields(String stdout, String prefix, int count) {
        List<String> matches = stdout.lines().filter(line -> line.startsWith(prefix)).toList();
        checkEq(1, matches.size(), "exactly one " + prefix + " row");
        if (matches.size() != 1) {
            return null;
        }
        String[] fields = matches.get(0).substring(prefix.length()).split("\\|", -1);
        checkEq(count, fields.length, "closed " + prefix + " row");
        return fields.length == count ? fields : null;
    }

    static int productionHostFailures() {
        return failed;
    }

    public static void main(String[] args) throws Exception {
        testRethrowAcrossHandler();
        testSavedCauseAliasMutation();
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
