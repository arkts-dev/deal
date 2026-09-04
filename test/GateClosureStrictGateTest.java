package deal.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Gate-closure strict gate regression pins (ISSUE-0477, ISSUE-0402
 * acceptance remediation).
 *
 * <p>Pins the in-runner strict gate re-landed in
 * {@code deal.test.ConformanceTest} (luajit-gate-closure D2/D5) — the
 * post-unit zero-fail assertion keyed on the staged-failure registry
 * shape. The re-landing closes the vacuousness hole the review cycle 2
 * found: before it, a re-added staged entry recorded a tracked
 * non-fatal {@code STAGED-FAIL} and the runner exited 0, so the
 * zero-staged requirement was silently re-openable. Each probe below
 * runs a compiled scratch copy of {@code test/ConformanceTest.java}
 * (its registry re-shaped as named) over a scratch conformance root
 * holding exactly the corpus-relative
 * {@code backend-runtime/stdlib-edge/time-now-millis-positive.deal}
 * fixture, in a subprocess whose working directory is the repository
 * root (the catalog validation resolves repository locators relative to
 * it). The three registry-shape modes of the gate are pinned exactly:</p>
 *
 * <ul>
 *   <li>Empty registry (strict mode, clean): the flipped fixture passes
 *       {@code runtime-error E8004}, no {@code GATE FAILURE} line
 *       prints, exit 0.</li>
 *   <li>The exact sanctioned pre-unit ISSUE-0237 pair (dormant): the
 *       runner's tracked non-fatal semantics are unchanged — the locked
 *       E8004 artifact records {@code STAGED-FAIL} (or the
 *       environmental SKIP when luajit is absent), no
 *       {@code GATE FAILURE} line prints, exit 0 — the closure is
 *       pending and never asserted.</li>
 *   <li>Any other shape (the re-added entry pinned to the flipped
 *       header): the dispatch records the tracked {@code STAGED-FAIL}
 *       first, then the shape check hard-fails regardless of the
 *       counters with the per-entry report and the removal
 *       instruction, exit 1.</li>
 * </ul>
 *
 * <p>A fourth probe pins the strict-mode residual report: with the
 * empty registry, a fixture failing its own expectation enumerates the
 * failed residual ({@code GATE FAILURE: 1 failed — <path> — <detail>})
 * and exits 1 (the environmental skip report is pinned when luajit is
 * absent).</p>
 *
 * <p>Execution contract: every scratch artifact lives in a fresh
 * temporary directory and is deleted afterwards; the repository files
 * {@code test/ConformanceTest.java} and the shared fixture are never
 * modified. The probes execute the compiled runner against real
 * LuaJIT when luajit is available on the gate machine.</p>
 */
public class GateClosureStrictGateTest {

    private static final String FIXTURE_REL =
        "backend-runtime/stdlib-edge/time-now-millis-positive.deal";

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    /** Subprocess result of one scratch-runner probe. */
    private static final class ProbeResult {
        final int exit;
        final String output;

        ProbeResult(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Gate Closure Strict Gate Tests (ISSUE-0477) ===\n");

        Path repo = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath().normalize();
        check(Files.isRegularFile(
                repo.resolve("build/deal/test/ConformanceTest.class")),
            "the delivered build must contain deal.test.ConformanceTest "
                + "(the gate must be compiled before this suite runs)");
        check(Files.isRegularFile(
                repo.resolve("test/ConformanceTest.java")),
            "the runner source test/ConformanceTest.java must be present");
        Path fixture = repo.resolve("test/conformance")
            .resolve(FIXTURE_REL);
        check(Files.isRegularFile(fixture),
            "the shared time fixture must exist at " + FIXTURE_REL);

        boolean luajit = luajitAvailable();

        // Probe 1 — empty registry (strict mode, clean): the delivered
        // runner over the flipped fixture.
        probeEmptyRegistryClean(repo, fixture);

        // Probe 2 — the exact sanctioned pre-unit pair (dormant mode).
        probeSanctionedPairDormant(repo, fixture, luajit);

        // Probe 3 — the re-added entry pinned to the flipped header
        // (non-sanctioned shape: hard failure regardless of the
        // counters).
        probeReAddedEntryShapeFailure(repo, fixture, luajit);

        // Probe 4 — strict-mode residual enumeration: a failing fixture
        // under the empty registry.
        probeStrictFailedResidual(repo, fixture, luajit);

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Probes
    // =========================================================================

    /** Probe 1: empty registry + flipped fixture -> PASS, exit 0, clean. */
    private static void probeEmptyRegistryClean(Path repo, Path fixture)
            throws Exception {
        Path scratch = Files.createTempDirectory("gc-empty-");
        try {
            Path coroot = scratch.resolve("coroot");
            copyFixture(fixture, coroot, "runtime-error E8004");
            ProbeResult result = runRunner(
                repo, null, scratch, coroot, "delivered-runner");
            check(result.exit == 0,
                "probe 1 (strict clean): expected exit 0, got "
                    + result.exit);
            check(result.output.contains(
                    "[backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
                    + "OK (found DEAL_ERROR_CODE: E8004)"),
                "probe 1 (strict clean): the flipped fixture must record "
                    + "PASS on the delivered runner");
            check(result.output.contains(
                    "Total: 1, Passed: 1, Failed: 0, Skipped: 0, "
                    + "KnownFailures (tracked): 0, StagedFailures (tracked): 0"),
                "probe 1 (strict clean): the summary must show the four "
                    + "zeros for the single-fixture corpus");
            check(!result.output.contains("GATE FAILURE"),
                "probe 1 (strict clean): strict mode must print no "
                    + "GATE FAILURE line");
        } finally {
            deleteTree(scratch);
        }
    }

    /** Probe 2: the sanctioned pre-unit pair -> dormant, exit 0. */
    private static void probeSanctionedPairDormant(Path repo, Path fixture,
            boolean luajit) throws Exception {
        Path scratch = Files.createTempDirectory("gc-dormant-");
        try {
            Path coroot = scratch.resolve("coroot");
            copyFixture(fixture, coroot, "runtime-ok");
            ProbeResult result = runRunner(repo,
                new String[] {
                    "stagedFailure(\"backend-runtime/stdlib-edge/"
                        + "time-now-millis-positive.deal\",",
                    "            \"runtime-ok\",",
                    "            \"E8004\",",
                    "            \"ISSUE-0237\",",
                    "            \"the retained std/time.nowMillis ()->int "
                        + "route raises E8004 for contemporary epoch "
                        + "milliseconds under the signed-int32 gate "
                        + "(locked TIME_NOW_MILLIS artifact)\");"
                },
                scratch, coroot, "sanctioned-pair-runner");
            check(result.exit == 0,
                "probe 2 (dormant): expected exit 0, got " + result.exit);
            check(!result.output.contains("GATE FAILURE"),
                "probe 2 (dormant): the sanctioned pair must keep the "
                    + "shape check dormant — no GATE FAILURE line");
            if (luajit) {
                check(result.output.contains(
                        "STAGED-FAIL (E8004 locked artifact; tracked by "
                        + "ISSUE-0237:"),
                    "probe 2 (dormant): the locked artifact must record "
                        + "the tracked STAGED-FAIL");
                check(result.output.contains(
                        "StagedFailures (tracked): 1"),
                    "probe 2 (dormant): the summary must track one "
                        + "staged failure");
            } else {
                check(result.output.contains("SKIP (LuaJIT not available)"),
                    "probe 2 (dormant): without luajit the environmental "
                        + "SKIP must be recorded");
            }
        } finally {
            deleteTree(scratch);
        }
    }

    /** Probe 3: re-added entry pinned to the flipped header -> shape
     * failure with the removal instruction, exit 1, regardless of the
     * tracked STAGED-FAIL recorded by the dispatch. */
    private static void probeReAddedEntryShapeFailure(Path repo, Path fixture,
            boolean luajit) throws Exception {
        Path scratch = Files.createTempDirectory("gc-readd-");
        try {
            Path coroot = scratch.resolve("coroot");
            copyFixture(fixture, coroot, "runtime-error E8004");
            ProbeResult result = runRunner(repo,
                new String[] {
                    "stagedFailure(\"backend-runtime/stdlib-edge/"
                        + "time-now-millis-positive.deal\",",
                    "            \"runtime-error E8004\",",
                    "            \"E8004\",",
                    "            \"ISSUE-0237\",",
                    "            \"re-introduction exercise: locked E8004 "
                        + "artifact\");"
                },
                scratch, coroot, "readded-entry-runner");
            check(result.exit == 1,
                "probe 3 (shape failure): expected exit 1, got "
                    + result.exit);
            check(result.output.contains(
                    "GATE FAILURE: staged-failure registry is neither the "
                    + "sanctioned pre-unit ISSUE-0237 pair nor empty — "
                    + "backend-runtime/stdlib-edge/"
                    + "time-now-millis-positive.deal (tracked by "
                    + "ISSUE-0237)"),
                "probe 3 (shape failure): the per-entry shape report must "
                    + "name the fixture path and the owning issue");
            check(result.output.contains(
                    "promotion instruction: remove the registry entry "
                    + "(or entries)"),
                "probe 3 (shape failure): the removal instruction must "
                    + "print");
            if (luajit) {
                check(result.output.contains(
                        "STAGED-FAIL (E8004 locked artifact; tracked by "
                        + "ISSUE-0237:"),
                    "probe 3 (shape failure): the dispatch must record "
                        + "the tracked STAGED-FAIL before the shape "
                        + "check fires");
                check(result.output.contains(
                        "StagedFailures (tracked): 1"),
                    "probe 3 (shape failure): the shape check must fire "
                        + "regardless of the non-fatal staged counter");
            }
        } finally {
            deleteTree(scratch);
        }
    }

    /** Probe 4: empty registry + a fixture failing its own expectation
     * -> strict-mode failed residual, exit 1 (skip residual when luajit
     * is absent). */
    private static void probeStrictFailedResidual(Path repo, Path fixture,
            boolean luajit) throws Exception {
        Path scratch = Files.createTempDirectory("gc-residual-");
        try {
            Path coroot = scratch.resolve("coroot");
            copyFixture(fixture, coroot, "runtime-error E9999");
            ProbeResult result = runRunner(
                repo, null, scratch, coroot, "delivered-runner");
            check(result.exit == 1,
                "probe 4 (strict residual): expected exit 1, got "
                    + result.exit);
            if (luajit) {
                check(result.output.contains(
                        "GATE FAILURE: 1 failed — "
                        + "backend-runtime/stdlib-edge/"
                        + "time-now-millis-positive.deal — expected "
                        + "DEAL_ERROR_CODE: E9999"),
                    "probe 4 (strict residual): the failed residual must "
                        + "name the fixture path and the recorded FAIL "
                        + "detail");
            } else {
                check(result.output.contains(
                        "GATE FAILURE: 1 skipped — "
                        + "backend-runtime/stdlib-edge/"
                        + "time-now-millis-positive.deal — LuaJIT "
                        + "unavailable on the gate machine; the LuaJIT "
                        + "lane cannot be verified (environmental probe "
                        + "branch)"),
                    "probe 4 (strict residual): without luajit the "
                        + "environmental skip residual must be named");
            }
        } finally {
            deleteTree(scratch);
        }
    }

    // =========================================================================
    // Scratch machinery
    // =========================================================================

    private static boolean luajitAvailable() {
        try {
            new ProcessBuilder("luajit", "-v").start()
                .waitFor(5, TimeUnit.SECONDS);
            return true;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Writes the time fixture into the scratch corpus root at its
     * corpus-relative path, keeping the repository fixture's body
     * byte-identical and replacing only the {@code @expected} line.
     * {@code expectation} is one of {@code runtime-ok},
     * {@code runtime-error E8004} (the landed header), or a scratch
     * variant like {@code runtime-error E9999} for the residual probe.
     */
    private static void copyFixture(Path fixture, Path coroot,
            String expectation) throws IOException {
        Path target = coroot.resolve(FIXTURE_REL);
        Files.createDirectories(target.getParent());
        String body = Files.readString(fixture);
        int expectedLine = -1;
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("// @expected:")) {
                expectedLine = i;
                break;
            }
        }
        if (expectedLine < 0) {
            throw new IllegalStateException(
                "fixture carries no @expected line");
        }
        lines[expectedLine] = "// @expected: " + expectation;
        Files.writeString(target, String.join("\n", lines));
    }

    /**
     * Compiles a scratch copy of {@code test/ConformanceTest.java} with
     * the staged-failure registry re-shaped (or left as delivered when
     * {@code entry} is {@code null}), then runs it in a subprocess whose
     * working directory is the repository root over the scratch corpus
     * root. The scratch-compiled class shadows the delivered one via
     * classpath order.
     */
    private static ProbeResult runRunner(Path repo, String[] entry,
            Path scratch, Path coroot, String tag) throws Exception {
        Path scratchTest = scratch.resolve("test");
        Files.createDirectories(scratchTest);
        Path runner = scratchTest.resolve("ConformanceTest.java");
        Files.writeString(runner, Files.readString(
            repo.resolve("test/ConformanceTest.java")));
        if (entry != null) {
            reshapeRegistry(runner, entry);
        }
        Path scratchBuild = scratch.resolve("build");
        Files.createDirectories(scratchBuild);
        ProcessBuilder javac = new ProcessBuilder(
            "javac", "--release", "25", "-proc:none",
            "-d", scratchBuild.toString(),
            "-cp", repo.resolve("build").toString(),
            runner.toString());
        javac.directory(repo.toFile());
        javac.redirectErrorStream(true);
        Process javacProcess = javac.start();
        String javacOutput = new String(
            javacProcess.getInputStream().readAllBytes()).trim();
        int javacExit = javacProcess.waitFor();
        if (javacExit != 0) {
            throw new IllegalStateException(
                "scratch runner compile failed (" + tag + "): "
                + javacOutput);
        }
        ProcessBuilder run = new ProcessBuilder(
            "java", "-ea",
            "-cp", scratchBuild + java.io.File.pathSeparator
                + repo.resolve("build"),
            "deal.test.ConformanceTest", coroot.toString());
        run.directory(repo.toFile());
        run.redirectErrorStream(true);
        Process process = run.start();
        String output = new String(
            process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        return new ProbeResult(exit, output);
    }

    /**
     * Replaces the empty {@code static { } } block of the registry with
     * a block holding one {@code stagedFailure(...)} registration whose
     * argument lines are {@code entry} (the call's first argument line
     * through its closing line).
     */
    private static void reshapeRegistry(Path runner, String[] entry)
            throws IOException {
        String src = Files.readString(runner);
        String emptyBlock =
            "        new LinkedHashMap<>();\n"
            + "    static {\n"
            + "    }\n";
        if (!src.contains(emptyBlock)) {
            throw new IllegalStateException(
                "runner registry block shape changed — the empty static "
                + "block was not found");
        }
        StringBuilder inserted = new StringBuilder();
        inserted.append("        new LinkedHashMap<>();\n");
        inserted.append("    static {\n");
        for (String line : entry) {
            inserted.append("        ").append(line).append("\n");
        }
        inserted.append("    }\n");
        src = src.replace(emptyBlock, inserted.toString());
        Files.writeString(runner, src);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream
                    .sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }
}
