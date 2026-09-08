package deal.test;

import deal.semantic.CapabilityRegistry;
import deal.semantic.CompilerInvocation;
import deal.semantic.CompilerProfileProvider;
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
 * JVM lane-state pin test (ISSUE-0378 D5) — the anti-hollow evidence

 * owner for the activated JVM corpus lane, re-pinned by the
 * disposition-application unit (ISSUE-0380) to the post-unit green
 * lane state.

 *
 * <p>The test owns two pin families:
 * <ul>
 *   <li><b>Invocation pin</b>:
 *       {@link JvmConformanceTest#laneInvocation()} equals,
 *       field-exactly, the explicit
 *       {@code CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,
 *       CapabilityRegistry.releaseRegistry())} invocation (purpose
 *       PUBLIC_BUILD, semantic profile {@code DEAL_V1_2_INT32}, release
 *       state {@code V1_2_ACTIVE}), and the lane source text carries
 *       zero catalog-seam {@code invocationFor} call sites.</li>
 *   <li><b>Exact-output pins</b>: the test runs the real JVM lane
 *       ({@code java -ea -cp build deal.test.JvmConformanceTest
 *       test/conformance/}) and the real LuaJIT lane
 *       ({@code java -ea -cp build deal.test.ConformanceTest
 *       test/conformance/}) as subprocesses and asserts the captured

 *       outputs field-exactly: both exit codes, the verbatim
 *       passing-fixture lines, the exact {@code ] FAIL (} line set
 *       (post-unit: only the two pre-existing host-prewrapped
 *       skip-probe exception lines on the JVM lane), zero GATE
 *       FAILURE lines, no STAGED-FAIL line on either lane, the green
 *       {@code Gates PASSED} banner on the JVM lane, and the pinned
 *       summary numbers. Every assertion matches captured real-run
 *       output text — never a paraphrase.</li>
 * </ul>
 *
 * <p>The post-unit JVM lane state: exit code 0 — the shared time
 * fixture flipped to {@code runtime-error E8004} passes under the
 * activated profile with {@code OK (found DEAL_ERROR_CODE: E8004)},
 * and the promoted
 * {@code backend-runtime/arithmetic/int-add-overflow.deal} passes the
 * same way, so the stale-known-fail gate no longer names it; only the
 * two host-prewrapped skip-probe exception lines remain in the
 * {@code ] FAIL (} set. ISSUE-0158
 * (the JVM bytes core lane) then promoted the nine bytes skip
 * entries — the direct bytes surface passes the real pipeline and
 * the stale-skip gate forced the entries out — so the summary moved
 * again to {@code passed 265, failed 0, skipped 36 ... pass rate
 * 88.0%} with the single retained bytes skip (the recursive
 * bytes-bearing wrapper closure, ISSUE-0160). The LuaJIT lane
 * (consequence, pinned): exit code 0 — the flipped time fixture
 * passes as {@code runtime-error E8004} under its legacy-authority
 * catalog row (zero v1.2 credit), the staged registry entry is
 * removed (no STAGED-FAIL line), the promoted int-add-overflow
 * passes, and the summary reads {@code Total: 496, Passed: 496}
 * (ISSUE-0501 lands the 63 compile-classified gap fixtures under
 * {@code frontend/}; ISSUE-0500 then co-lands the preserved gap
 * resolution subtree and the transformed direct declaration fixture:
 * 545 discovered, 49 companions, 496 recorded results; this tree
 * promotes the last known-fail — the frontend FFI-manifest fixture —
 * to compile-error E2010, so zero known-fail remains tracked) and
 * zero staged failures.

 *
 * <p>The test runs from the repository root (the {@code run_tests.sh}
 * contract, like {@code ConformanceTest}); {@code run_tests.sh}
 * substitutes this test for the two raw lane launches, so both real
 * lanes still run — inside this test — and their exact outputs are
 * asserted on every gate run.</p>
 */
public class JvmLaneStatePinTest {

    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    // The pinned JVM lane output set (exact captured output text)
    // =========================================================================

    private static final String JVM_TIME_OK =
        "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
            + "OK (found DEAL_ERROR_CODE: E8004)";


    private static final String JVM_ADD_OVERFLOW_OK =

        "  [backend-runtime/arithmetic/int-add-overflow.deal] OK "
            + "(found DEAL_ERROR_CODE: E8004)";

    private static final String JVM_PREWRAPPED_OK =
        "  [backend-runtime/host-abi/host-prewrapped-ok.deal] FAIL "
            + "(execution exception): no JVM host implementation for "
            + "prewrapped_ok";

    private static final String JVM_PREWRAPPED_BAD =
        "  [backend-runtime/host-abi/host-prewrapped-bad.deal] FAIL "
            + "(execution exception): no JVM host implementation for "
            + "prewrapped_bad";

    private static final String JVM_GATES_PASSED =
        "Gates PASSED: frontend 100%; backend-runtime zero applicable "

            + "failures AND >= 80% pass rate over the unchanged 301-test "
            + "denominator; zero unclassified skips; zero stale skips; "
            + "zero stale known-fail markers; zero probe runner "
            + "exceptions.";


    // ISSUE-0380 (the disposition-application unit) flipped the time
    // fixture to runtime-error E8004 and promoted int-add-overflow, so
    // the summary moved from "passed 254, failed 1 ... pass rate 84.4%"
    // to "passed 256, failed 0 ... pass rate 85.0%". ISSUE-0158 (the
    // JVM bytes core lane) then promoted the nine bytes skip entries
    // (only the recursive bytes-bearing wrapper-closure fixture stays
    // skipped, ISSUE-0160), so the summary moved to the re-pinned
    // numbers below.
    private static final String JVM_SUMMARY =
        "Backend-runtime on JVM: denominator 301 (every on-disk runtime "
            + "test, unchanged), passed 265, failed 0, skipped 36 "
            + "(classified), known-fail 0 (tracked) \u2014 pass rate 88.0%";


    private static final String JVM_PROFILE_AUTHORITY =
        "Profile-authority accounting: 0 legacy-authority fixture(s) "
            + "(LEGACY_REGRESSION + LEGACY_SAFE_INT \u2014 zero "
            + "v1.2/promotion credit; 0 passed, 0 failed)";

    // =========================================================================
    // The pinned LuaJIT lane post-unit state (exact captured output text)
    // =========================================================================

    private static final String LUA_TIME_OK =
        "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
            + "OK (found DEAL_ERROR_CODE: E8004)";


    private static final String LUA_ADD_OVERFLOW_OK =
        "  [backend-runtime/arithmetic/int-add-overflow.deal] OK "
            + "(found DEAL_ERROR_CODE: E8004)";

    // ISSUE-0500 (v12-gap-suite-integration E1/E2 co-landing): the
    // LuaJIT lane discovers 545 fixtures after the ISSUE-0501 landing
    // (528 + 17 frontend fixtures: the preserved resolution subtree
    // with its nine companions and the transformed direct declaration
    // fixture), so the summary reads 496 recorded results
    // (545 - 49 companions, zero tracked known-fail: this tree
    // promoted the FFI-manifest frontend pin to compile-error E2010).
    private static final String LUA_SUMMARY =

        "Total: 496, Passed: 496, Failed: 0, Skipped: 0, "
            + "KnownFailures (tracked): 0, StagedFailures (tracked): 0";

    private static final String LUA_PHASE =
        "  LuaJIT backend-runtime conformance (v1.2): 307/307 passed, "
            + "0 failed, 0 skipped, 0 known-fail (tracked), 0 "
            + "staged-fail (tracked)";

    private static final String LUA_FOLLOW_UP =
        "  Tracked v1.2 follow-up issues: none \u2014 full v1.2 "
            + "conformance";

    private static final String LUA_PROFILE_AUTHORITY =
        "Profile-authority accounting: 1 legacy-authority result(s) "
            + "(LEGACY_REGRESSION + LEGACY_SAFE_INT \u2014 zero "
            + "v1.2/promotion credit; 1 passed, 0 failed), 544 v1.2-credit "
            + "result(s) (COMMON_SHADOW + DEAL_V1_2_INT32)";


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

    /** The multiset of {@code ] FAIL (} lines, compared exactly
     * (duplicates included) — an extra, missing, or reworded failure
     * line diverges from the pinned set and fails. */
    private static void checkFailLineSet(String output,
            List<String> expectedLines, String lane) {
        List<String> actual = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            if (line.contains("] FAIL (")) {
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
            System.err.println("FAIL: " + lane + " ] FAIL ( line set "
                + "diverges from the pinned set.\nexpected: " + expected
                + "\nactual:   " + actual);
        }
    }

    private static void checkGateLineSet(String output,
            List<String> expectedLines, String lane) {
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
            System.err.println("FAIL: " + lane + " GATE FAILURE line set "
                + "diverges from the pinned set.\nexpected: " + expected
                + "\nactual:   " + actual);
        }
    }

    // =========================================================================
    // Subprocess runs (the real lanes)
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
    // activated invocation and zero catalog-seam call sites)
    // =========================================================================

    private static void pinInvocation() throws IOException {
        System.out.println("-- Invocation pin --");
        CompilerInvocation lane = JvmConformanceTest.laneInvocation();
        CompilerInvocation resolved = CompilerProfileProvider.resolve(
            ReleaseState.V1_2_ACTIVE, CapabilityRegistry.releaseRegistry());
        check(lane.equals(resolved),
            "JvmConformanceTest.laneInvocation() must equal, "
                + "field-exactly, the explicit "
                + "CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE, "
                + "CapabilityRegistry.releaseRegistry()) invocation");
        check(lane.purpose() == InvocationPurpose.PUBLIC_BUILD,
            "the lane invocation purpose must be PUBLIC_BUILD, got "
                + lane.purpose());
        check(lane.semanticProfile() == SemanticProfile.DEAL_V1_2_INT32,
            "the lane invocation profile must be DEAL_V1_2_INT32, got "
                + lane.semanticProfile());
        check(lane.releaseState() == ReleaseState.V1_2_ACTIVE,
            "the lane invocation release state must be V1_2_ACTIVE, got "
                + lane.releaseState());

        String laneSource = Files.readString(
            Path.of("test/JvmConformanceTest.java"));
        String compactLaneSource = laneSource.replaceAll("\\s+", "");
        check(compactLaneSource.contains(
            "CompilerProfileProvider.resolve(ReleaseState.V1_2_ACTIVE,"
                + "CapabilityRegistry.releaseRegistry())"),
            "the lane source must carry the explicit "
                + "CompilerProfileProvider.resolve(V1_2_ACTIVE, "
                + "releaseRegistry()) invocation");
        check(!laneSource.contains("invocationFor"),
            "the lane source must carry zero "
                + "LegacyProfileRegressionCatalog.invocationFor call "
                + "sites");
        check(laneSource.contains("LANE_INVOCATION"),
            "the lane source must expose the LANE_INVOCATION constant");
    }

    // =========================================================================
    // JVM lane exact-output pin
    // =========================================================================

    private static void pinJvmLane() throws IOException, InterruptedException {
        System.out.println("-- JVM lane exact-output pin (real subprocess "
            + "run) --");
        RunResult run = runLane("deal.test.JvmConformanceTest",
            "test/conformance/");
        check(run.exitCode() == 0,

            "the JVM lane must exit 0 post-unit (the flipped time "
                + "fixture and the promoted int-add-overflow pass under "
                + "the activated profile), got " + run.exitCode());

        String out = run.output();
        checkFailLineSet(out, List.of(
            JVM_PREWRAPPED_OK, JVM_PREWRAPPED_BAD), "JVM lane");
        checkGateLineSet(out, List.of(), "JVM lane");
        checkContains(out, JVM_TIME_OK, "JVM lane");

        checkContains(out, JVM_ADD_OVERFLOW_OK, "JVM lane");

        checkContains(out, JVM_SUMMARY, "JVM lane");
        checkContains(out, JVM_PROFILE_AUTHORITY, "JVM lane");
        checkContains(out, JVM_GATES_PASSED, "JVM lane");
        check(!out.contains("STAGED-FAIL"),
            "the JVM lane carries no staged-failure registry: its output "
                + "must contain no STAGED-FAIL line");

        check(!out.contains("GATE FAILURE"),
            "the JVM lane must print no GATE FAILURE line post-unit");

    }

    // =========================================================================
    // LuaJIT lane exact-output pin (consequence, never papered)
    // =========================================================================

    private static void pinLuaJitLane()
            throws IOException, InterruptedException {
        System.out.println("-- LuaJIT lane exact-output pin (real "
            + "subprocess run) --");
        RunResult run = runLane("deal.test.ConformanceTest",
            "test/conformance/");
        check(run.exitCode() == 0,

            "the LuaJIT lane must exit 0 post-unit (the flipped time "
                + "fixture passes as runtime-error E8004 under its "
                + "legacy-authority catalog row and the promoted "
                + "int-add-overflow passes; the staged registry entry "
                + "is removed), got " + run.exitCode());

        String out = run.output();
        checkFailLineSet(out, List.of(), "LuaJIT lane");
        checkGateLineSet(out, List.of(), "LuaJIT lane");
        checkContains(out, LUA_TIME_OK, "LuaJIT lane");

        checkContains(out, LUA_ADD_OVERFLOW_OK, "LuaJIT lane");
        checkContains(out, LUA_SUMMARY, "LuaJIT lane");
        checkContains(out, LUA_PHASE, "LuaJIT lane");
        checkContains(out, LUA_FOLLOW_UP, "LuaJIT lane");
        checkContains(out, LUA_PROFILE_AUTHORITY, "LuaJIT lane");
        check(!out.contains("STAGED-FAIL"),
            "the LuaJIT lane must carry no STAGED-FAIL line post-unit "
                + "(the staged registry entry was removed by the unit)");

    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== JVM Lane State Pin Test (ISSUE-0378 D5 "
            + "anti-hollow evidence) ===\n");

        pinInvocation();
        pinJvmLane();
        pinLuaJitLane();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
