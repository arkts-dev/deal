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
 * completion gate closure (ISSUE-0307) to the closed lane state:
 * zero skips, 100% of the on-disk denominator, no registry.
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
 *       zero catalog-seam {@code invocationFor} call sites AND zero
 *       skip-registry surface (no {@code JVM-GAP-} token, no
 *       {@code SkipEntry}, no {@code SKIPS} registry — the registry is
 *       retired, not retained empty — zero skips by construction).</li>
 *   <li><b>Exact-output pins</b>: the test runs the real JVM lane
 *       ({@code java -ea -cp build deal.test.JvmConformanceTest
 *       test/conformance/}) and the real LuaJIT lane
 *       ({@code java -ea -cp build deal.test.ConformanceTest
 *       test/conformance/}) as subprocesses and asserts the captured
 *       outputs field-exactly: both exit codes, the exact
 *       {@code ] FAIL (} line set (EMPTY on both lanes), zero GATE
 *       FAILURE lines, no SKIP line, no STAGED-FAIL line, the green
 *       {@code Gates PASSED} banner, and the pinned summary numbers.
 *       Every assertion matches captured real-run output text — never
 *       a paraphrase.</li>
 * </ul>
 *
 * <p>The closed JVM lane state (ISSUE-0307): exit code 0 — every one
 * of the 375 on-disk applicable backend-runtime fixtures passes the
 * real pipeline (denominator 375, passed 375, failed 0, skipped 0,
 * known-fail 0, pass rate 100.0%). The four final gap families closed
 * with their dispositions: the Error literal default filling (the
 * builtin {@code code}/{@code message} defaults to {@code ""}), the
 * Error-typed nullable catch-probe local and catch-block assignment,
 * the host export used as a first-class function value (the per-export
 * shared wrapper carrier keeps the identical E8010 boundary check), and
 * the eight ISSUE-0504 host-boundary fixtures (the boxed int/Integer
 * carrier class literals match the declared host shapes). ISSUE-0550
 * (the dynamic boundary rows) then lands the six dynamic
 * bytes-boundary fixtures — the two runtime-ok fixtures and the four
 * runtime-error fixtures whose pinned E8001/E8003/E8010 codes match
 * the real JVM emissions at the code-level needle — so the closed
 * summary moves to denominator 360, passed 360, pass rate 100.0%.
 * ISSUE-0507
 * (FFI candidate fixture conformance) then adds the sixteen FFI
 * fixtures under {@code backend-runtime/ffi/}: the fifteen
 * runtime-classified fixtures pass the real pipeline as
 * compile-reject E6006 FFI_UNSUPPORTED_BACKEND (the sanctioned C6
 * divergence) and the async-declaration fixture passes the frontend
 * gate as E7002, so the summary moved again to denominator 375,
 * passed 375, failed 0, skipped 0, known-fail 0, pass rate 100.0%.
 * The LuaJIT lane (consequence, pinned): exit code 0 with
 * {@code Total: 571, Passed: 571} (624 discovered - 53 companions)
 * with the backend-runtime phase at 382/382, and the
 * profile-authority accounting reads 623 v1.2-credit results
 * (624 - 1 legacy).
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

    private static final String JVM_GATES_PASSED =
        "Gates PASSED: frontend 100%; backend-runtime zero applicable "
            + "failures AND 100% of the unchanged 375-test denominator "
            + "through codegen, javac, and java; zero skips (no registry "
            + "\u2014 zero by construction); zero stale known-fail markers; "
            + "zero probe runner exceptions.";


    // ISSUE-0307 (the completion gate closure) retired the last eleven
    // skip entries with their dispositions: the Error literal default
    // filling (rtc-015), the Error-typed nullable catch-probe local and
    // catch-block assignment (plan-phase-order), the host export used as
    // a first-class function value (host-async-shape-value — the
    // per-export shared wrapper carrier keeps the identical E8010
    // boundary check, so the fixture passes its pinned runtime-error
    // E8010), and the eight ISSUE-0504 host-boundary fixtures (the boxed
    // int/Integer carrier class literals now match the declared host
    // shapes under DEAL_V1_2_INT32). The registry itself is retired —
    // removed, never retained empty — so the gate records skipped 0 by
    // construction and the closed summary reads 354/354 at 100.0%.
    // ISSUE-0550 (the dynamic boundary rows) then lands the six dynamic
    // bytes-boundary fixtures — the two runtime-ok fixtures
    // (bytes-dynamic-boundary-ok, bytes-dynamic-nullable-function-ok)
    // and the four runtime-error fixtures (the pinned E8001/E8003/E8010
    // codes match the real JVM emissions at the code-level needle, so
    // they need no skip entry here) — so the closed summary moves to
    // 360/360 at 100.0%.
    // ISSUE-0507 (FFI candidate fixture conformance) then adds the
    // fifteen runtime-classified FFI fixtures, every one passing the
    // real pipeline as the sanctioned compile-reject E6006
    // FFI_UNSUPPORTED_BACKEND divergence (the async-declaration
    // fixture is frontend-classified and never enters this
    // denominator), so the closed summary moves again to the re-pinned
    // numbers below (denominator 375, passed 375, skipped 0, pass rate
    // 100.0%).
    private static final String JVM_SUMMARY =
        "Backend-runtime on JVM: denominator 375 (every on-disk runtime "
            + "test, unchanged), passed 375, failed 0, skipped 0 (no "
            + "registry \u2014 zero skips by construction), known-fail 0 "
            + "(tracked) \u2014 pass rate 100.0%";

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
    // LuaJIT lane discovers 588 fixtures after the ISSUE-0501 landing
    // plus the ISSUE-0547 bytes-container fixtures plus the ISSUE-0502
    // gap-suite runtime population (30 runtime-ok — the promoted gap
    // bytes-boundary-order included, its retained-known-fail marker
    // forced off by the zero-skip promotion gate after ISSUE-0158
    // lifted the E3019 bytes-equality gate), 10 runtime-error, and 1
    // companion — so the summary reads 538 recorded results
    // (588 discovered - 50 companions, zero tracked known-fail: this
    // tree promoted the FFI-manifest frontend pin to compile-error
    // E2010 and the promoted bytes-boundary-order fixture counts as a
    // real runtime-ok fixture). ISSUE-0504 (the host ABI conversion
    // leaf) then lands the eight host-boundary fixtures, so the
    // summary reads 546 recorded results (596 discovered - 50
    // companions) and the backend-runtime phase reads 357/357; the
    // three ISSUE-0160 recursive bytes-closure corpus fixtures then
    // land on top (599 discovered), so the summary reads 549 recorded
    // results and the backend-runtime phase reads 360/360; the six
    // ISSUE-0550 dynamic boundary-row fixtures then land on top (605
    // discovered), so the summary reads 555 recorded results and the
    // backend-runtime phase reads 366/366. ISSUE-0507
    // (FFI candidate fixture conformance) then adds the nineteen FFI
    // files — the sixteen fixtures (ten runtime-ok, five runtime-error,
    // one compile-error E7002) plus their three companion support
    // declarations — so the summary reads 571 recorded results
    // (624 discovered - 53 companions, zero tracked known-fail) and the
    // backend-runtime phase reads 382/382.
    private static final String LUA_SUMMARY =
        "Total: 571, Passed: 571, Failed: 0, Skipped: 0, "
            + "KnownFailures (tracked): 0, StagedFailures (tracked): 0";

    private static final String LUA_PHASE =
        "  LuaJIT backend-runtime conformance (v1.2): 382/382 passed, "
            + "0 failed, 0 skipped, 0 known-fail (tracked), 0 "
            + "staged-fail (tracked)";

    private static final String LUA_FOLLOW_UP =
        "  Tracked v1.2 follow-up issues: none \u2014 full v1.2 "
            + "conformance";

    private static final String LUA_PROFILE_AUTHORITY =
        "Profile-authority accounting: 1 legacy-authority result(s) "
            + "(LEGACY_REGRESSION + LEGACY_SAFE_INT \u2014 zero "
            + "v1.2/promotion credit; 1 passed, 0 failed), 623 v1.2-credit "
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
                + "releaseRegistry()) invocation");
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

        // ISSUE-0307 gate closure: the skip registry is RETIRED — the
        // lane source carries no registry surface and no gap language
        // (zero skips by construction, never empty skip machinery —
        // jvm-v12-completion-architecture D3).
        check(!laneSource.contains("JVM-GAP-"),
            "the lane source must carry zero gap-language tokens "
                + "(the skip registry is retired, not retained empty)");
        check(!laneSource.contains("SkipEntry")
                && !laneSource.contains("SKIPS"),
            "the lane source must carry no skip-registry surface "
                + "(no SkipEntry record, no SKIPS map)");
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
        checkFailLineSet(out, List.of(), "JVM lane");
        checkGateLineSet(out, List.of(), "JVM lane");
        checkContains(out, JVM_TIME_OK, "JVM lane");

        checkContains(out, JVM_ADD_OVERFLOW_OK, "JVM lane");

        checkContains(out, JVM_SUMMARY, "JVM lane");
        checkContains(out, JVM_PROFILE_AUTHORITY, "JVM lane");
        checkContains(out, JVM_GATES_PASSED, "JVM lane");
        check(!out.contains("STAGED-FAIL"),
            "the JVM lane carries no staged-failure registry: its output "
                + "must contain no STAGED-FAIL line");
        check(!out.contains("SKIP ("),
            "the closed JVM lane prints no SKIP line — the registry is "
                + "retired and every fixture passes the real pipeline");

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
