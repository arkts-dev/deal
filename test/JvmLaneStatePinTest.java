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
 * owner for the activated JVM corpus lane's sanctioned staged state.
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
 *       failure sets field-exactly: both exit codes, the verbatim
 *       fixture names, the verbatim promotion instruction, the exact
 *       GATE FAILURE set, the exact {@code ] FAIL (} line set, no
 *       STAGED-FAIL line on the JVM lane, the LuaJIT staged entry
 *       intact, and the pinned summary numbers. Every assertion
 *       matches captured real-run output text — never a paraphrase.</li>
 * </ul>
 *
 * <p>The sanctioned pinned JVM lane state (the unmerged tree): exit
 * code 1; exactly two gate failures — the unflipped stdlib-edge
 * epoch-millisecond fixture raising E8004 at the declared {@code int}
 * boundary against its {@code runtime-ok} expectation (one applicable
 * failure), and the restored corpus known-fail fixture
 * {@code backend-runtime/arithmetic/int-add-overflow.deal} firing the
 * stale-known-fail gate with the promotion instruction (set
 * {@code @expected: runtime-error E8004}, drop the {@code @issue} tag)
 * — plus the two pre-existing host-prewrapped skip-probe exception
 * lines and nothing else. The LuaJIT lane (consequence, pinned): exit
 * code 1 with the same stale known-fail and its single tracked staged
 * time entry (ISSUE-0237) intact.
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
    // The pinned JVM lane failure set (exact captured output text)
    // =========================================================================

    private static final String JVM_TIME_FAIL =
        "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
            + "FAIL (runtime-ok test exited 1): DEAL_ERROR_CODE: E8004 "
            + "int out of safe range";

    private static final String JVM_STALE_KNOWN_FAIL =
        "  [backend-runtime/arithmetic/int-add-overflow.deal] FAIL "
            + "(STALE known-fail: the v1.2 requirement tracked by "
            + "ISSUE-0111 now passes on JVM \u2014 promote the fixture: set "
            + "'@expected: runtime-error E8004' and drop the @issue "
            + "tag)";

    private static final String JVM_PREWRAPPED_OK =
        "  [backend-runtime/host-abi/host-prewrapped-ok.deal] FAIL "
            + "(execution exception): no JVM host implementation for "
            + "prewrapped_ok";

    private static final String JVM_PREWRAPPED_BAD =
        "  [backend-runtime/host-abi/host-prewrapped-bad.deal] FAIL "
            + "(execution exception): no JVM host implementation for "
            + "prewrapped_bad";

    private static final String JVM_GATE_STALE =
        "GATE FAILURE: 1 stale known-fail marker(s) \u2014 promote the "
            + "fixture(s)";

    private static final String JVM_GATE_APPLICABLE =
        "GATE FAILURE: 1 applicable backend-runtime test(s) failed \u2014 "
            + "zero applicable failures required";

    private static final String JVM_SUMMARY =
        "Backend-runtime on JVM: denominator 301 (every on-disk runtime "
            + "test, unchanged), passed 246, failed 1, skipped 53 "
            + "(classified), known-fail 0 (tracked) \u2014 pass rate 81.7%";

    private static final String JVM_PROFILE_AUTHORITY =
        "Profile-authority accounting: 0 legacy-authority fixture(s) "
            + "(LEGACY_REGRESSION + LEGACY_SAFE_INT \u2014 zero "
            + "v1.2/promotion credit; 0 passed, 0 failed)";

    // =========================================================================
    // The pinned LuaJIT lane consequence (exact captured output text)
    // =========================================================================

    private static final String LUA_STALE_KNOWN_FAIL =
        "  [backend-runtime/arithmetic/int-add-overflow.deal] FAIL "
            + "(STALE known-fail: the v1.2 requirement tracked by "
            + "ISSUE-0111 now passes \u2014 promote the fixture: set "
            + "'@expected: runtime-error E8004' and drop the @issue "
            + "tag)";

    private static final String LUA_STAGED_TIME =
        "  [backend-runtime/stdlib-edge/time-now-millis-positive.deal] "
            + "LEGACY-AUTHORITY (legacy-regression; zero v1.2 credit) "
            + "STAGED-FAIL (E8004 locked artifact; tracked by "
            + "ISSUE-0237: the retained std/time.nowMillis ()->int route "
            + "raises E8004 for contemporary epoch milliseconds under "
            + "the signed-int32 gate (locked TIME_NOW_MILLIS artifact); "
            + "the fixture's runtime-ok expectation and std/time.lua are "
            + "frozen until the delegated time-selector child lands its "
            + "disposition pair)";

    private static final String LUA_SUMMARY =
        "Total: 424, Passed: 423, Failed: 1, Skipped: 0, "
            + "KnownFailures (tracked): 0, StagedFailures (tracked): 1";

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
        check(run.exitCode() == 1,
            "the JVM lane must exit 1 on the sanctioned pinned staged "
                + "state, got " + run.exitCode());
        String out = run.output();
        checkFailLineSet(out, List.of(
            JVM_TIME_FAIL, JVM_STALE_KNOWN_FAIL,
            JVM_PREWRAPPED_OK, JVM_PREWRAPPED_BAD), "JVM lane");
        checkGateLineSet(out,
            List.of(JVM_GATE_STALE, JVM_GATE_APPLICABLE), "JVM lane");
        checkContains(out, JVM_SUMMARY, "JVM lane");
        checkContains(out, JVM_PROFILE_AUTHORITY, "JVM lane");
        check(!out.contains("STAGED-FAIL"),
            "the JVM lane carries no staged-failure registry: its output "
                + "must contain no STAGED-FAIL line");
        check(!out.contains("Gates PASSED"),
            "the JVM lane must not print the green Gates PASSED banner "
                + "on the sanctioned pinned staged state");
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
        check(run.exitCode() == 1,
            "the LuaJIT lane must exit 1 with the stale known-fail, got "
                + run.exitCode());
        String out = run.output();
        checkFailLineSet(out, List.of(LUA_STALE_KNOWN_FAIL), "LuaJIT lane");
        checkGateLineSet(out, List.of(), "LuaJIT lane");
        checkContains(out, LUA_STAGED_TIME, "LuaJIT lane");
        checkContains(out, LUA_SUMMARY, "LuaJIT lane");
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
