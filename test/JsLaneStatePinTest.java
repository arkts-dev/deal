package deal.test;

import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.InvocationPurpose;
import deal.semantic.ir.ReleaseState;
import deal.semantic.ir.SemanticProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JS lane state pin test (ISSUE-0536 — the ISSUE-0372 acceptance
 * remediation; post-completion pins flipped by ISSUE-0331) — the
 * anti-hollow evidence owner for the JS corpus gate
 * ({@code deal.test.JsConformanceTest}), launched on every gate run
 * under its lane-wide activated invocation.
 *
 * <p>The test owns two pin families:
 * <ul>
 *   <li><b>Invocation pin</b>: {@link JsConformanceTest#laneInvocation()}
 *       equals, field-exactly, the explicit
 *       {@code CompilerProfileProvider.resolveCommonShadow(
 *       SemanticProfile.DEAL_V1_2_INT32, ReleaseState.PRE_ACTIVATION,
 *       ReleaseConfiguration.releaseCapabilityRegistry())} invocation
 *       (purpose {@code COMMON_SHADOW}, semantic profile
 *       {@code DEAL_V1_2_INT32}, release state {@code PRE_ACTIVATION})
 *       — the invocation the LuaJIT lane's A5 seam resolves for every
 *       uncatalogued fixture — and the lane source passes it through the
 *       {@link deal.module.CompilationOrchestrator} constructor.</li>
 *   <li><b>Exact-output pins</b>: the test runs the real JS corpus gate
 *       ({@code java -ea -cp build deal.test.JsConformanceTest
 *       test/conformance/}) as a subprocess and asserts the captured
 *       state field-exactly: the gate is CLOSED (ISSUE-0331 — the JS
 *       completion gate closure) — exit code 0, the shared time fixture
 *       passes as {@code OK (found DEAL_ERROR_CODE: E8004)} under the
 *       activated invocation (the gate validity condition
 *       {@code expectation(fixture) == landed std/time.js behavior}
 *       holds — js-v12-completion-architecture D5), the frontend and
 *       companion gates are green (6/6 and 29/29), all 343
 *       node-executed backend-runtime tests pass with zero failures and
 *       zero skips (pass rate 100.0%), and the {@code Gates PASSED}
 *       line prints. The three former owner-delegated lane divergences
 *       closed in the same unit: the E8003 array-element walk over
 *       json-array-marked Map tables and the E8007 defaults-map seam
 *       ({@code deal/runtime.js} {@code checkArray}/{@code loadHost},
 *       with the {@code cfg.js} triplet carrying the Lua-mirroring
 *       {@code $rt.MISSING} marks), so no {@code ] FAIL (} line and no
 *       {@code GATE FAILURE} line may appear.</li>
 * </ul>
 *
 * <p>The test runs from the repository root (the {@code run_tests.sh}
 * contract, like {@code ConformanceTest}); {@code run_tests.sh}
 * launches this test, and the real JS corpus gate runs — inside this
 * test — on every gate run.</p>
 */
public class JsLaneStatePinTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // The pinned JS gate output state (exact captured output text)
    // =========================================================================

    /** The shared time fixture passes on the JS gate under the activated
     * invocation — the required result of the ISSUE-0536 remediation,
     * unchanged by the ISSUE-0331 closure. */
    private static final String JS_TIME_OK =
        "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
            + "OK (found DEAL_ERROR_CODE: E8004)";

    /** The ISSUE-0331 closure summary lines — the gate is closed: every
     * node-executed backend-runtime fixture passes, zero skips, 100%
     * of the denominator, and the Gates PASSED line prints. */
    private static final String SUMMARY_FRONTEND =
        "Frontend (backend-neutral compile-ok/compile-error): total 6, "
            + "passed 6, failed 0";
    private static final String SUMMARY_RUNTIME =
        "Backend-runtime on Node: denominator 343 (every on-disk "
            + "runtime-ok/runtime-error test plus every known-fail "
            + "probe), passed 343, failed 0, skipped 0 (no skip registry "
            + "— zero skips by construction), known-fail 0 (tracked), "
            + "node subprocess runs 343 — pass rate 100.0%";
    private static final String SUMMARY_COMPANIONS =
        "classified 29 (on-disk @expected: companion 29), passed 29, "
            + "failed 0";
    private static final String GATES_PASSED_LINE =
        "Gates PASSED: frontend 100%; backend-runtime on node zero "
            + "applicable failures AND 100% of the node-executed 343-test"
            + " denominator; zero skipped (no skip registry); zero stale "
            + "known-fail markers; companion counts equal the on-disk "
            + "corpus and every companion standalone-compiles and "
            + "participates in its importers' temp projects; zero probe "
            + "runner exceptions.";

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    private static void checkContains(String output, String expected,
            String lane) {
        if (output.contains(expected)) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + lane + " output must contain "
                + "exactly:\n" + expected
                + "\n--- captured output (excerpt) ---\n"
                + output.substring(0, Math.min(output.length(), 2000)));
        }
    }

    /** The closed gate emits no {@code ] FAIL (} line and no
     * {@code GATE FAILURE} line — exactly zero of each. */
    private static void checkZeroFailureLines(String output) {
        List<String> failLines = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            if (line.contains("] FAIL (") || line.contains("GATE FAILURE")) {
                failLines.add(line);
            }
        }
        if (failLines.isEmpty()) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: the closed JS gate must print zero "
                + "] FAIL ( / GATE FAILURE lines, got: " + failLines);
        }
    }

    // =========================================================================
    // Subprocess run (the real JS corpus gate)
    // =========================================================================

    private record RunResult(int exitCode, String output) {}

    private static RunResult runLane(String mainClass, String... extraArgs)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-ea");
        command.add("-cp");
        command.add(Path.of("build").toAbsolutePath().normalize().toString());
        command.add(mainClass);
        for (String extra : extraArgs) {
            command.add(extra);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new RunResult(exit, output);
    }

    // =========================================================================
    // Invocation pin (field-exact; the lane source carries the explicit
    // activated invocation and passes it through the orchestrator)
    // =========================================================================

    private static void pinInvocation() throws IOException {
        System.out.println("-- Invocation pin --");
        CompilerInvocation lane = JsConformanceTest.laneInvocation();
        CompilerInvocation resolved = CompilerProfileProvider
            .resolveCommonShadow(SemanticProfile.DEAL_V1_2_INT32,
                ReleaseState.PRE_ACTIVATION,
                ReleaseConfiguration.releaseCapabilityRegistry());
        check(lane.equals(resolved),
            "JsConformanceTest.laneInvocation() must equal, field-exactly, "
                + "the explicit resolveCommonShadow(DEAL_V1_2_INT32, "
                + "PRE_ACTIVATION, releaseCapabilityRegistry()) invocation");
        check(lane.purpose() == InvocationPurpose.COMMON_SHADOW,
            "the lane invocation purpose must be COMMON_SHADOW, got "
                + lane.purpose());
        check(lane.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32,
            "the lane invocation profile must be DEAL_V1_2_INT32, got "
                + lane.semanticProfile());
        check(lane.releaseState() == ReleaseState.PRE_ACTIVATION,
            "the lane invocation release state must be PRE_ACTIVATION, "
                + "got " + lane.releaseState());

        String laneSource = Files.readString(
            Path.of("test/JsConformanceTest.java"));
        String compactLaneSource = laneSource.replaceAll("\\s+", "");
        check(compactLaneSource.contains(
            "CompilerProfileProvider.resolveCommonShadow("
                + "SemanticProfile.DEAL_V1_2_INT32,"
                + "ReleaseState.PRE_ACTIVATION,"
                + "ReleaseConfiguration.releaseCapabilityRegistry())"),
            "the lane source must carry the explicit "
                + "resolveCommonShadow(DEAL_V1_2_INT32, PRE_ACTIVATION, "
                + "releaseCapabilityRegistry()) invocation");
        check(compactLaneSource.contains("REPO_ROOT,null,LANE_INVOCATION"),
            "the lane source must pass LANE_INVOCATION through the "
                + "CompilationOrchestrator constructor");
        check(laneSource.contains("LANE_INVOCATION"),
            "the lane source must expose the LANE_INVOCATION constant");
    }

    // =========================================================================
    // JS gate exact-output pin
    // =========================================================================

    private static void pinJsGate() throws IOException, InterruptedException {
        System.out.println("-- JS gate exact-output pin (real subprocess "
            + "run) --");
        RunResult run = runLane("deal.test.JsConformanceTest",
            "test/conformance/");
        check(run.exitCode() == 0,
            "the closed JS gate exits 0 (the ISSUE-0331 completion gate "
                + "closure: all 343 node-executed fixtures pass, zero "
                + "skips), got " + run.exitCode());

        String out = run.output();
        checkZeroFailureLines(out);
        checkContains(out, JS_TIME_OK, "JS gate");
        checkContains(out, SUMMARY_FRONTEND, "JS gate");
        checkContains(out, SUMMARY_RUNTIME, "JS gate");
        checkContains(out, SUMMARY_COMPANIONS, "JS gate");
        checkContains(out, GATES_PASSED_LINE, "JS gate");
        check(!out.contains("FAIL (classification failure)"),
            "the JS gate prints no classification failure line");
        check(!out.contains("TOOL_MISSING"),
            "the JS gate prints no tool-missing line (node is a hard "
                + "requirement, never a skip)");
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== JS Lane State Pin Test (ISSUE-0536 "
            + "anti-hollow evidence; ISSUE-0331 closure pins) ===\n");

        pinInvocation();
        pinJsGate();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
