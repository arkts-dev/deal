package deal.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * std/time.nowMillis pre-activation branch-1 state pin (ISSUE-0369).
 *
 * <p>Pins the repository's exact pre-activation state selected by branch 1
 * (std-time-nowmillis-resolution-and-disposition D1/D2): the retained
 * {@code ()->int} implementations on all three backends, the shared corpus
 * fixture still declaring {@code // @expected: runtime-ok} with the
 * positive-timestamp description, the runtime seam shape (no time-related
 * member, no JS-only legacy-range member, {@code checkInt} the single int
 * range seam), the fixture's absence from the JVM skip registry, and the
 * two legacy slice pins ({@code jvm-std-time-nowmillis},
 * {@code js-stdlib-time-structural}) with unchanged source and expected
 * result.
 *
 * <p>The behavioral half of the pre-activation state — the fixture passing
 * {@code runtime-ok} on the LuaJIT lane ({@code deal.test.ConformanceTest})
 * and the JVM lane ({@code deal.test.JvmConformanceTest}), the direct
 * suites ({@code luajit test_stdlib.lua}, {@code node test_stdlib_js.js})
 * green with their nowMillis cases, and the slice pins green
 * ({@code deal.test.BackendConformanceTest}) — is exercised on every gate
 * run by {@code run_tests.sh}. The JS lane has no corpus runner at this
 * state: {@code test/JsConformanceTest.java} is compiled but never launched
 * by {@code run_tests.sh} — a recorded pre-activation fact
 * (js-v12-completion-architecture D5), not a failure.
 *
 * <p>Lifecycle: the assertions pinning the pre-activation value of the two
 * surfaces the disposition-application unit is authorized to flip — the
 * fixture header ({@code @expected: runtime-ok}) and the unparameterized
 * runtime seam ({@code checkInt}'s single ±(2^53-1) arm, no
 * {@code setInt32Mode}/{@code $int32}) — are retired/updated in that single
 * unit together with the canonical {@code runtime-error E8004} header flip
 * and the three int32-gate activations, which must keep
 * {@code ./run_tests.sh} green at every landing (D4/D5). The retained
 * implementation texts, the runtime's no-time-member shape, the skip
 * registry absence, and the legacy slice pins are permanent under the epic
 * and must keep passing unchanged.
 */
public class StdlibTimePreActivationPinTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== std/time.nowMillis Pre-Activation Pin Tests (ISSUE-0369) ===\n");
        testRetainedJsImplementation();
        testRetainedLuaImplementation();
        testRetainedJvmEmission();
        testRuntimeSeam();
        testFixtureHeader();
        testJvmSkipRegistryAbsence();
        testLegacySlicePins();
        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // =========================================================================
    // Retained implementations (byte-identical under the epic, D2)
    // =========================================================================

    private static void testRetainedJsImplementation() throws IOException {
        System.out.println("-- std/time.js retained ()->int wrapper --");
        List<String> ls = Files.readAllLines(Path.of("std/time.js"));
        check(ls.size() >= 34,
            "std/time.js has at least 34 lines (got " + ls.size() + ")");
        if (ls.size() >= 34) {
            check("$time.nowMillis = $rt.function(\"()->int\", function($file, $line, $column) {"
                    .equals(ls.get(32)),
                "std/time.js:33 is the retained ()->int wrapper line");
            check("  return $rt.checkInt(($Math.trunc($Date.now() / 1000)) * 1000, $file, $line, $column);"
                    .equals(ls.get(33)),
                "std/time.js:34 is the retained checkInt return line");
        }
        String text = Files.readString(Path.of("std/time.js"));
        check(count(text, "return $rt.checkInt(($Math.trunc($Date.now() / 1000)) * 1000, "
                + "$file, $line, $column);") == 1,
            "the retained exit check appears exactly once in std/time.js");
    }

    private static void testRetainedLuaImplementation() throws IOException {
        System.out.println("-- std/time.lua retained check_int exit check --");
        List<String> ls = Files.readAllLines(Path.of("std/time.lua"));
        check(ls.size() >= 10,
            "std/time.lua has at least 10 lines (got " + ls.size() + ")");
        if (ls.size() >= 10) {
            check("time.nowMillis = __rt.function_(\"()->int\", function()".equals(ls.get(8)),
                "std/time.lua:9 is the retained ()->int wrapper line");
            check("  return __rt.check_int(os.time() * 1000)".equals(ls.get(9)),
                "std/time.lua:10 is the retained check_int return line");
        }
        String text = Files.readString(Path.of("std/time.lua"));
        check(count(text, "__rt.check_int(os.time() * 1000)") == 1,
            "the retained exit check appears exactly once in std/time.lua");
    }

    private static void testRetainedJvmEmission() throws IOException {
        System.out.println("-- JvmBackend emitStdlibTimeMemberCall retained emission --");
        String text = Files.readString(Path.of("deal/codegen/jvm/JvmBackend.java"));
        check(text.contains("emitStdlibTimeMemberCall"),
            "emitStdlibTimeMemberCall exists in JvmBackend.java");
        check(count(text, "return \"(java.lang.System.currentTimeMillis() / 1000L) * 1000L\";") == 1,
            "emitStdlibTimeMemberCall returns the retained second-truncated "
                + "epoch-millis expression exactly once");
    }

    // =========================================================================
    // Runtime seam: no time member, no legacy-range member, checkInt single
    // =========================================================================

    private static void testRuntimeSeam() throws IOException {
        System.out.println("-- deal/runtime.js seam shape --");
        String text = Files.readString(Path.of("deal/runtime.js"));
        check(!text.contains("nowMillis"),
            "deal/runtime.js has no nowMillis member");
        check(!text.contains("Date"),
            "deal/runtime.js has no Date/time member");
        check(!text.contains("currentTimeMillis"),
            "deal/runtime.js has no currentTimeMillis reference");
        check(!text.contains("setInt32Mode"),
            "deal/runtime.js has no setInt32Mode selector yet");
        check(!text.contains("$int32"),
            "deal/runtime.js has no $int32 flag yet");
        check(text.contains("if (v < -9007199254740991 || v > 9007199254740991) {"),
            "checkInt's range arm is the retained ±(2^53-1) gate");
        check(count(text, "v < -9007199254740991") == 1
                && count(text, "v > 9007199254740991") == 1,
            "each ±(2^53-1) bound appears exactly once — checkInt is the "
                + "single int range seam, no JS-only legacy-range member");
        check(!text.contains("2147483647") && !text.contains("2147483648"),
            "no signed-32 boundary constant exists yet — no second range gate");
    }

    // =========================================================================
    // Shared corpus fixture: still runtime-ok, no disposition flip applied
    // =========================================================================

    private static void testFixtureHeader() throws IOException {
        System.out.println("-- shared corpus fixture header --");
        Path fixture = Path.of(
            "test/conformance/backend-runtime/stdlib-edge/time-now-millis-positive.deal");
        List<String> ls = Files.readAllLines(fixture);
        check(ls.size() >= 4,
            "the fixture has at least 4 lines (got " + ls.size() + ")");
        if (ls.size() >= 4) {
            check("// @spec: Standard library declarations — std/time".equals(ls.get(0)),
                "the fixture keeps the canonical @spec line");
            check("// @description: std/time.nowMillis returns a positive int-like timestamp"
                    .equals(ls.get(1)),
                "the fixture keeps the positive-timestamp @description");
            check("// @expected: runtime-ok".equals(ls.get(2)),
                "the fixture still declares @expected: runtime-ok");
            check("// @features: stdlib, time".equals(ls.get(3)),
                "the fixture keeps the @features line");
        }
        String text = Files.readString(fixture);
        check(!text.contains("runtime-error"),
            "the fixture contains no runtime-error expectation "
                + "(no disposition flip applied)");
        check(count(text, "@expected") == 1,
            "the fixture has exactly one @expected line");
    }

    // =========================================================================
    // JVM skip registry: the fixture is executed, never skipped
    // =========================================================================

    private static void testJvmSkipRegistryAbsence() throws IOException {
        System.out.println("-- JVM skip registry --");
        String text = Files.readString(Path.of("test/JvmConformanceTest.java"));
        check(!text.contains("time-now-millis-positive"),
            "the time fixture is absent from the JVM skip registry");
    }

    // =========================================================================
    // Legacy slice pins: unchanged source and expected result (D2.5)
    // =========================================================================

    private static void testLegacySlicePins() throws IOException {
        System.out.println("-- legacy slice pins --");
        String jvm = Files.readString(
            Path.of("test/conformance/fixtures/jvm-stdlib-slice.json"));
        check(jvm.contains("\"name\": \"jvm-std-time-nowmillis\""),
            "jvm-stdlib-slice.json carries the jvm-std-time-nowmillis case");
        check(jvm.contains("let t: int = time.nowMillis();"),
            "jvm-std-time-nowmillis keeps its source (let t: int = time.nowMillis())");
        check(jvm.contains("t > 1700000000000 && granularity === 0"),
            "jvm-std-time-nowmillis keeps its positive-timestamp assertion");
        check(jvm.contains("\"expectedOutput\": \"1\""),
            "jvm-std-time-nowmillis keeps its expected output");
        check(jvm.contains("\"expectedError\": null"),
            "jvm-std-time-nowmillis keeps its expected error (null)");

        String js = Files.readString(
            Path.of("test/conformance/fixtures/js-skeleton.json"));
        check(js.contains("\"name\": \"js-stdlib-time-structural\""),
            "js-skeleton.json carries the js-stdlib-time-structural case");
        check(js.contains("let t: int = time.nowMillis();"),
            "js-stdlib-time-structural keeps its source (let t: int = time.nowMillis())");
        check(js.contains("let again: int = time.nowMillis();"),
            "js-stdlib-time-structural keeps its second nowMillis call");
        check(js.contains("if (again >= t) { return 1; }"),
            "js-stdlib-time-structural keeps its monotonicity assertion");
        check(js.contains("\"expectedOutput\": \"1\""),
            "js-stdlib-time-structural keeps its expected output");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
