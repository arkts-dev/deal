package deal.test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The required-tool fail-closed negative control (ISSUE-0874).
 *
 * <p>The verification contract has no skip path: a required tool's absence is
 * the named {@code TOOL_MISSING} infrastructure outcome and fails the run,
 * because a lane that never executed cannot evidence a supported case. This
 * suite proves the production mechanisms on both sides of that contract:</p>
 *
 * <ol>
 *   <li>{@code tools/tool-preflight.sh} passes only when every named tool
 *       resolves, and otherwise reports one {@code TOOL_MISSING: <tool>} line
 *       per absent tool and exits nonzero;</li>
 *   <li>{@code tools/gate-manifest.sh} refuses a skip or warning line inside a
 *       record, so a reintroduced skip can never silently remove a case from
 *       the selection;</li>
 *   <li>{@code run_tests.sh} applies that preflight to its whole required set
 *       before any build work, so an absent LuaJIT or Node stops the gate with
 *       the named outcome and touches no build output;</li>
 *   <li>every JS production-artifact suite fails with the same outcome instead
 *       of skipping its Node drives.</li>
 * </ol>
 *
 * <p>Each case drives the real script or suite in a bounded child whose
 * {@code PATH} holds exactly the tool set the case needs, so the absence is
 * created rather than assumed.</p>
 */
public final class ToolchainPreflightTest {

    private static final Path REPO = Path.of("").toAbsolutePath().normalize();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Required-Tool Fail-Closed Preflight Tests (ISSUE-0874) ===\n");
        Path scratch = Files.createTempDirectory("deal-tool-preflight-");
        try {
            testPreflightPassesWithEveryToolPresent(scratch);
            testPreflightFailsOnOneAbsentTool(scratch);
            testPreflightReportsEveryAbsentTool(scratch);
            testManifestRefusesSkipRecords(scratch);
            testRunnerFailsBeforeBuilding(scratch);
            testSuitesFailWithTheNamedOutcome(scratch);
        } finally {
            deleteRecursively(scratch);
        }
        System.out.println();
        System.out.println("Passed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("=== Required-Tool Fail-Closed Preflight Tests Passed ===");
    }

    // =========================================================================
    // The shell preflight
    // =========================================================================

    /** Every named tool present: the preflight passes silently. */
    private static void testPreflightPassesWithEveryToolPresent(Path scratch)
            throws Exception {
        System.out.println("-- the preflight passes when every named tool resolves --");
        Path stub = stubTools(scratch, "all-present", List.of("luajit", "node"));
        var run = runWithPath(
            List.of(shell(), "tools/tool-preflight.sh", "luajit", "node"), stub);
        checkEq(0, run.exitCode(), "the preflight accepts the present tools: "
            + run.stderr());
        checkEq("", run.stderr(), "the preflight reports nothing on success");
        checkEq("", run.stdout(), "the preflight prints nothing on success");
    }

    /** One absent tool: exactly that tool is named and the preflight fails. */
    private static void testPreflightFailsOnOneAbsentTool(Path scratch)
            throws Exception {
        System.out.println("-- the preflight fails on an absent luajit --");
        Path stub = stubTools(scratch, "node-only", List.of("node"));
        var run = runWithPath(
            List.of(shell(), "tools/tool-preflight.sh", "luajit", "node"), stub);
        checkEq(1, run.exitCode(), "an absent required tool fails the preflight");
        check(run.stderr().contains("TOOL_MISSING: luajit"),
            "the absent tool is named with the infrastructure outcome: "
                + run.stderr());
        check(!run.stderr().contains("TOOL_MISSING: node"),
            "the present tool is not reported: " + run.stderr());
        check(!run.stderr().contains("WARNING")
                && !run.stderr().contains("not found, skipping"),
            "the preflight carries no warning or skip record: " + run.stderr());
        checkEq("", run.stdout(), "the preflight keeps stdout empty on failure");
    }

    /** Every tool absent: each one is named, and the exit status is nonzero. */
    private static void testPreflightReportsEveryAbsentTool(Path scratch)
            throws Exception {
        System.out.println("-- the preflight names every absent required tool --");
        Path empty = Files.createDirectories(scratch.resolve("no-tools"));
        var run = runWithPath(
            List.of(shell(), "tools/tool-preflight.sh", "luajit", "node"), empty);
        checkEq(1, run.exitCode(), "the preflight fails with nothing on PATH");
        check(run.stderr().contains("TOOL_MISSING: luajit")
                && run.stderr().contains("TOOL_MISSING: node"),
            "both absent tools are reported: " + run.stderr());
    }

    // =========================================================================
    // The manifest
    // =========================================================================

    /**
     * The selection refuses a skip or warning record: the real manifest loads,
     * and the same manifest with one reintroduced warning line fails instead of
     * downgrading the run.
     */
    private static void testManifestRefusesSkipRecords(Path scratch)
            throws Exception {
        System.out.println("-- the manifest refuses a skip/warning record --");
        Path real = REPO.resolve("tools/gate-manifest.sh");
        var loaded = BoundedProcess.runCaptured(
            List.of(shell(), "-c",
                "set -e; source '" + real + "'; echo manifest-sourced"),
            REPO, Map.of());
        checkEq(0, loaded.exitCode(),
            "the real manifest sources cleanly: " + loaded.stderr());
        check(loaded.stdout().contains("manifest-sourced"),
            "the real manifest selection loads: " + loaded.stdout());

        String nodeRecord = "  'node|=== Running Standard Library JS Tests ==="
            + "|node test_stdlib_js.js'";
        String source = Files.readString(real);
        check(source.contains(nodeRecord),
            "the manifest declares the Node stdlib runtime record");
        Path injected = Files.createDirectories(scratch.resolve("manifest-skip"))
            .resolve("gate-manifest.sh");
        Files.writeString(injected, source.replace(nodeRecord,
            nodeRecord.substring(0, nodeRecord.length() - 1)
                + "\nWARNING: node not found, skipping standard library JS"
                + " tests'"));
        var refused = BoundedProcess.runCaptured(
            List.of(shell(), "-c",
                "set -e; source '" + injected + "'; echo manifest-sourced"),
            REPO, Map.of());
        checkEq(1, refused.exitCode(),
            "a skip/warning record fails the manifest: " + refused.stdout());
        check(refused.stderr().contains(
                "INTERNAL ERROR: gate-manifest record carries a skip/warning line"),
            "the manifest names the refused record: " + refused.stderr());
        check(!refused.stdout().contains("manifest-sourced"),
            "the refused selection never loads: " + refused.stdout());
    }

    // =========================================================================
    // The runner
    // =========================================================================

    /**
     * The runner preflights its required set before any build work: with javac,
     * java and gcc present but luajit and node absent, it names both and stops
     * before the compile phase, leaving every build artifact untouched.
     */
    private static void testRunnerFailsBeforeBuilding(Path scratch)
            throws Exception {
        System.out.println("-- the runner stops with TOOL_MISSING before the build --");
        Path build = REPO.resolve("build");
        boolean buildBefore = Files.isDirectory(build);
        Path stub = stubTools(scratch, "no-runtime-tools",
            List.of("javac", "java", "gcc"));
        var run = runWithPath(
            List.of(shell(), "run_tests.sh", "--jobs", "1"), stub);
        checkEq(1, run.exitCode(), "an absent required tool fails the runner: "
            + run.stderr());
        check(run.stderr().contains("TOOL_MISSING: luajit")
                && run.stderr().contains("TOOL_MISSING: node"),
            "the runner reports both absent runtime tools: " + run.stderr());
        check(!run.stdout().contains("Compiling all DEAL sources"),
            "the runner stops before the compile phase: " + run.stdout());
        check(!run.stderr().contains("WARNING")
                && !run.stdout().contains("skipping"),
            "the runner carries no warning or skip path: " + run.stdout());
        checkEq(buildBefore, Files.isDirectory(build),
            "the runner touches no build output before the preflight");
    }

    // =========================================================================
    // The suites
    // =========================================================================

    /**
     * Every JS production-artifact suite fails with the named outcome when node
     * is absent — never a recorded skip and never a passing summary.
     */
    private static void testSuitesFailWithTheNamedOutcome(Path scratch)
            throws Exception {
        System.out.println("-- the JS production suites fail instead of skipping --");
        Path noTools = Files.createDirectories(scratch.resolve("suite-no-node"));
        String classpath = absoluteClasspath();
        for (String suite : List.of("deal.test.JsBackendTest",
                "deal.test.JsSemanticEmitterTest",
                "deal.test.ProductionProjectEmissionTest")) {
            var run = runWithPath(
                List.of(javaBinary(), "-cp", classpath, suite), noTools);
            checkEq(1, run.exitCode(), suite + " fails when node is absent: "
                + run.stderr());
            check(run.stderr().contains("TOOL_MISSING: node"),
                suite + " reports the named infrastructure outcome: "
                    + run.stderr());
            check(!run.stdout().contains("Skipped")
                    && !run.stdout().contains("SKIP"),
                suite + " records no skip: " + run.stdout());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static BoundedProcess.BoundedResult runWithPath(List<String> argv,
            Path path) throws IOException, InterruptedException {
        return BoundedProcess.runCaptured(argv, REPO, Map.of("PATH", path.toString()));
    }

    /** A PATH directory holding executable stubs for exactly {@code tools}. */
    private static Path stubTools(Path scratch, String name, List<String> tools)
            throws IOException {
        Path dir = Files.createDirectories(scratch.resolve(name));
        for (String tool : tools) {
            Path stub = dir.resolve(tool);
            Files.writeString(stub, "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(stub,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return dir;
    }

    /** The bash the gate scripts declare, resolved without a PATH lookup. */
    private static String shell() {
        for (String candidate : List.of("/bin/bash", "/usr/bin/bash")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        throw new AssertionError("the gate scripts require bash");
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
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

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    private static void checkEq(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual),
            message + " (expected " + expected + ", got " + actual + ")");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
