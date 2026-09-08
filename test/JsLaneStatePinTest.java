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
 * remediation) — the anti-hollow evidence owner for the JS corpus gate
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
 *       state field-exactly: the shared time fixture passes as
 *       {@code OK (found DEAL_ERROR_CODE: E8004)} under the activated
 *       invocation (the gate validity condition
 *       {@code expectation(fixture) == landed std/time.js behavior}
 *       holds — js-v12-completion-architecture D5), the frontend and
 *       companion gates are green (6/6 and 28/28), and the exact
 *       failure set is the four owner-delegated lane divergences below —
 *       each pinned by the differential gate's committed full-run
 *       enumeration — with exit code 1 (the gate's own gates still fail
 *       on them; the sanctioned pre-completion state is visible, never
 *       greenwashed).</li>
 * </ul>
 *
 * <p>The four owner-delegated divergences (each outside this
 * remediation's surface: {@code deal/runtime.js} stays byte-identical
 * under the resolution, and the JS lane contracts own them):
 * <ol>
 *   <li>{@code arithmetic/int32-mod-min-neg-one.deal} — the JS runtime's
 *       pinned {@code intMod} contract (js-v12-int32-bytes D1:
 *       {@code MIN_VALUE % -1} raises E8004 on JS) diverges from the
 *       shared fixture's spec-v1.2 remainder rule ({@code runtime-ok},
 *       landed with ISSUE-0397 I6; the LuaJIT runtime carries the I4
 *       int32 branch).</li>
 *   <li>{@code class-runtime-errors/dynamic-bad-class-array-element-e8001.deal}
 *       and {@code type-system/dynamic-array-element-e8003.deal} — the JS
 *       runtime's {@code checkArray} rejects a json-marked Map table with
 *       E8001 "expected array" where the shared fixtures pin the E8003
 *       element walk (the std/json → array boundary).</li>
 *   <li>{@code host-abi/host-class-extra-field.deal} — the JS
 *       {@code loadHost} defaults thunk augments the host defaults with
 *       {@code $MISSING} for declared optional fields, so a provided
 *       declared optional absent from {@code <C>_defaults} passes where
 *       the shared fixture pins E8007 (host-module-abi D2's preserved
 *       defaults-map seam).</li>
 * </ol>
 * Every other JS-applicable fixture passes; the printed summary pins
 * the exact counters.
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
     * invocation — the required result of the ISSUE-0536 remediation. */
    private static final String JS_TIME_OK =
        "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
            + "OK (found DEAL_ERROR_CODE: E8004)";

    /** The four owner-delegated lane divergences (the exact
     * {@code ] FAIL (} fixture-path multiset; each is pinned by the
     * differential gate's committed full-run enumeration). */
    private static final List<String> PINNED_FAIL_PATHS = List.of(
        "backend-runtime/arithmetic/int32-mod-min-neg-one.deal",
        "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
        "backend-runtime/host-abi/host-class-extra-field.deal",
        "backend-runtime/type-system/dynamic-array-element-e8003.deal");

    /** The gate's own failure lines — exactly these two, never more,
     * never fewer (the sanctioned pre-completion state). */
    private static final List<String> PINNED_GATE_FAILURES = List.of(
        "GATE FAILURE: 4 applicable backend-runtime test(s) failed — "
            + "zero applicable failures required",
        "GATE FAILURE: node-executed pass rate 98.7% below the 100% "
            + "threshold (denominator 301)");

    private static final String SUMMARY_FRONTEND =
        "Frontend (backend-neutral compile-ok/compile-error): total 6, "
            + "passed 6, failed 0";
    private static final String SUMMARY_RUNTIME =
        "Backend-runtime on Node: denominator 301 (every on-disk "
            + "runtime-ok/runtime-error test plus every known-fail "
            + "probe), passed 297, failed 4, skipped 0 (no skip registry "
            + "— zero skips by construction), known-fail 0 (tracked), "
            + "node subprocess runs 301 — pass rate 98.7%";
    private static final String SUMMARY_COMPANIONS =
        "classified 28 (on-disk @expected: companion 28), passed 28, "
            + "failed 0";

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

    /** The multiset of failing fixture paths (the {@code [...]} bracket
     * content of every {@code ] FAIL (} line), compared exactly — an
     * extra, missing, or renamed failure diverges from the pinned set
     * and fails. */
    private static void checkFailPathSet(String output,
            List<String> expectedPaths) {
        List<String> actual = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            int failIdx = line.indexOf("] FAIL (");
            if (failIdx < 0) continue;
            int openIdx = line.lastIndexOf('[', failIdx);
            if (openIdx < 0) continue;
            actual.add(line.substring(openIdx + 1, failIdx));
        }
        List<String> expected = new ArrayList<>(expectedPaths);
        java.util.Collections.sort(actual);
        java.util.Collections.sort(expected);
        if (actual.equals(expected)) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: JS gate ] FAIL ( path set diverges "
                + "from the pinned set.\nexpected: " + expected
                + "\nactual:   " + actual);
        }
    }

    /** The multiset of {@code GATE FAILURE} lines, compared exactly. */
    private static void checkGateFailureLineSet(String output,
            List<String> expectedLines) {
        List<String> actual = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            if (line.contains("GATE FAILURE")) {
                actual.add(line);
            }
        }
        List<String> expected = new ArrayList<>(expectedLines);
        java.util.Collections.sort(actual);
        java.util.Collections.sort(expected);
        if (actual.equals(expected)) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: JS gate GATE FAILURE line set "
                + "diverges from the pinned set.\nexpected: " + expected
                + "\nactual:   " + actual);
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
        check(run.exitCode() == 1,
            "the JS gate exits 1 in the sanctioned pre-completion state "
                + "(the four owner-delegated lane divergences still fail "
                + "its own gates; the state is visible, never "
                + "greenwashed), got " + run.exitCode());

        String out = run.output();
        checkFailPathSet(out, PINNED_FAIL_PATHS);
        checkGateFailureLineSet(out, PINNED_GATE_FAILURES);
        checkContains(out, JS_TIME_OK, "JS gate");
        checkContains(out, SUMMARY_FRONTEND, "JS gate");
        checkContains(out, SUMMARY_RUNTIME, "JS gate");
        checkContains(out, SUMMARY_COMPANIONS, "JS gate");
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
            + "anti-hollow evidence) ===\n");

        pinInvocation();
        pinJsGate();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
