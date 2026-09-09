package deal.test.conformance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Differential gate lanes corpus test (ISSUE-0357 Verification — the
 * cluster integration verification for the gate stage): the complete
 * three-lane gate over the real on-disk corpus through the gate core's
 * dispatch/verdict path — real {@code luajit}, {@code javac}+{@code java},
 * and {@code node} subprocesses — plus the six controlled divergence
 * experiments on scratch copies (never the committed corpus).
 *
 * <p>The full-run pins (Verification 1/8, pre-flip accounting G8):</p>
 * <ul>
 *   <li>the designated converged subset
 *       ({@code control-flow/if-else.deal},
 *       {@code functions/direct-recursion.deal},
 *       {@code stdlib/string/length-unicode.deal},
 *       {@code error-handling/try-catch.deal} plus two more converged
 *       cases) passes byte-exact on all three lanes;</li>
 *   <li>the failure set is exactly the tracked non-fatal set (the
 *       39 JVM registry entries) plus the enumerated differential
 *       failures — every one naming fixture,
 *       backend, and the closed mismatch class with the first differing
 *       byte/field detail;</li>
 *   <li>no {@code SKIP} verdict class appears anywhere (the gate has no
 *       skip branch; {@code Skipped: 0}).</li>
 * </ul>
 *
 * <p>The scratch experiments (Verification 1/4/5/6/7/8):</p>
 * <ol>
 *   <li>a passing fixture whose console output diverges on exactly one
 *       backend (a flagged perturbing lane double) fails naming that
 *       backend and the first differing byte;</li>
 *   <li>scratch lane variants printing a return value, invoking
 *       {@code main} twice, reordering exports, and writing framing to
 *       stderr fail the transcript comparison;</li>
 *   <li>scratch sidecars perturbing one error field, pinning an absent
 *       optional field, and violating the canonical serialization fail
 *       with the exact class and field/byte naming;</li>
 *   <li>scratch Compile Expectation Sidecars with a perturbed
 *       code/line/column/message fail
 *       {@code COMPILE_DIAGNOSTIC_MISMATCH} naming the fixture and the
 *       field; a second error diagnostic fails the fixture;</li>
 *   <li>scratch divergent sidecars for a non-FFI fixture or with a
 *       non-E6006 code are rejected by schema validation;</li>
 *   <li>a scratch slow fixture exceeds the harness-owned deadline and
 *       fails {@code LANE_TIMEOUT} with process termination on every
 *       lane.</li>
 * </ol>
 *
 * <p><b>Anti-hollow:</b> every lane of every run here is the production
 * lane — the LuaJIT lane compiles through the real frontend +
 * {@code LuaBackend} and executes real {@code luajit}, the JVM lane runs
 * the real whole-project orchestrator pipeline + {@code javac} +
 * {@code java}, and the JS lane runs the real {@code JsBackend} +
 * deployed runtime/stdlib + {@code node}. The only lane variations are
 * the flagged test-double subclasses of the real lanes used for the
 * controlled experiments, exactly as the lane suites do.</p>
 *
 * <p>The gate stays a dev-time tool in this stage (G5): the legacy
 * runners keep executing the corpus in {@code run_tests.sh} — the
 * temporary-coexistence window — and this test does not wire the gate
 * into the release surface.</p>
 */
public class DifferentialGateLanesCorpusTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    /** The harness-owned deadline of the full run (G7). */
    private static final Duration FULL_RUN_DEADLINE = Duration.ofSeconds(120);

    /** The pinned corpus population (the T2/T3/T5 pins; the frontend
     * population counts the promoted FFI-manifest compile-error
     * fixture — ISSUE-0477 dropped its known-fail marker; ISSUE-0547
     * adds the two bytes-container runtime fixtures, so the runtime
     * case population grows by two). */
    private static final int TOTAL_FIXTURES = 547;
    private static final int RUNTIME_CASES = 303;
    private static final int FRONTEND_COMPILED = 191;
    private static final int COMPILE_PINS = 4;

    /** Pre-flip accounting pins (G8; the last on-disk known-fail
     * marker was promoted by ISSUE-0477, so the counter is zero, and
     * the JVM bytes core lane promoted the nine bytes registry
     * entries, so the registry count is the landed 39). */
    private static final int KNOWN_FAILURES_TRACKED = 0;
    private static final int SKIP_REGISTRY_ENTRIES = 39;

    /** The per-backend pass/fail counters of the full run. */
    private static final Map<String, int[]> PER_BACKEND = Map.of(
        "luajit", new int[] {271, 32},
        "jvm", new int[] {201, 102},
        "js", new int[] {272, 31});

    /** The designated converged subset (task criterion (a)): every lane
     * of every fixture here passes byte-exact. */
    private static final List<String> CONVERGED_SUBSET = List.of(
        "backend-runtime/control-flow/if-else.deal",
        "backend-runtime/functions/direct-recursion.deal",
        "backend-runtime/stdlib/string/length-unicode.deal",
        "backend-runtime/error-handling/try-catch.deal",
        "backend-runtime/error-handling/throw-error.deal",
        "backend-runtime/control-flow/continue.deal");

    /** The tracked non-fatal registry set (the live pre-flip JVM
     * registry entries, every one still failing on the jvm lane). */
    private static final Set<String> TRACKED_REGISTRY = Set.of(
        "backend-runtime/bytes/bytes-descriptor-boundary.deal",
        "backend-runtime/bytes/bytes-index-bounds.deal",
        "backend-runtime/bytes/bytes-write-range.deal",
        "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal",
        "backend-runtime/class-runtime-errors/dynamic-bad-class-param-e8001.deal",
        "backend-runtime/class-runtime-errors/dynamic-bad-class-return-e8001.deal",
        "backend-runtime/class-runtime-errors/dynamic-bad-imported-class-param-e8001.deal",
        "backend-runtime/class-runtime-errors/dynamic-bad-nullable-class-e8001.deal",
        "backend-runtime/defaults/plan-host-discriminator.deal",
        "backend-runtime/defaults/plan-imported-provider-scope.deal",
        "backend-runtime/defaults/plan-phase-order-provided-before-defaults.deal",
        "backend-runtime/host-abi/host-array-return-ok.deal",
        "backend-runtime/host-abi/host-async-shape-value.deal",
        "backend-runtime/host-abi/host-boundary-apply-function.deal",
        "backend-runtime/host-abi/host-class-default-isolation.deal",
        "backend-runtime/host-abi/host-class-export.deal",
        "backend-runtime/host-abi/host-class-extra-field.deal",
        "backend-runtime/host-abi/host-export-presence.deal",
        "backend-runtime/host-abi/host-nullable-function-param-bad.deal",
        "backend-runtime/host-abi/host-nullable-function-param.deal",
        "backend-runtime/host-abi/host-nullable-function-return-bad.deal",
        "backend-runtime/host-abi/host-nullable-function-return-ok.deal",
        "backend-runtime/host-abi/host-prewrapped-bad.deal",
        "backend-runtime/host-abi/host-rest-bad.deal",
        "backend-runtime/host-abi/host-rest-ok.deal",
        "backend-runtime/jsonable/jsonable-fromjson-top-level-scalar.deal",
        "backend-runtime/jsonable/jsonable-optional-nullable-nested-class.deal",
        "backend-runtime/jsonable/jsonable-table-field-nested-arrays.deal",
        "backend-runtime/jsonable/nested-array-roundtrip.deal",
        "backend-runtime/runtime-errors/json-stringify-function-e8001.deal",
        "backend-runtime/source-location-precision/class-param-error-source.deal",
        "backend-runtime/source-location/bytes-index-bounds-source.deal",
        "backend-runtime/source-location/bytes-write-range-source.deal",
        "backend-runtime/source-location/json-error-source.deal",
        "backend-runtime/stdlib/json/int32-boundary-parse.deal",
        "backend-runtime/stdlib/json/json-stringify-bytes-error.deal",
        "backend-runtime/stdlib/json/json-stringify-roundtrip.deal",
        "backend-runtime/stdlib/table/keys-nonstring-exclusion.deal",
        "backend-runtime/type-system/dynamic-array-element-e8003.deal");

    /** The enumerated differential failures of the full run — every
     * non-tracked failing lane outcome as {@code fixture | backend |
     * mismatch class}. Anything outside this set or missing from it
     * fails the test (the failure set is exactly this enumeration). */
    private static final Set<String> DIFFERENTIAL_FAILURES = Set.of(
        "backend-runtime/arithmetic/int32-mod-min-neg-one.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int32-pow-infinity.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int32-pow-overflow.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-add-overflow.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-add-overflow.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-add-overflow.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-conversion-out-of-range.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-conversion-out-of-range.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-conversion-out-of-range.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-convert-fraction.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-convert-infinity.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-convert-nan.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-div-zero.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-mul-overflow.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-mul-overflow.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-mul-overflow.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-neg-min.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-neg-min.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-neg-min.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-pow-negative.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-sub-overflow.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/arithmetic/int-sub-overflow.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arithmetic/int-sub-overflow.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/arrays/index-negative-read.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arrays/index-negative-write.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arrays/index-oob.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/arrays/index-write-gap.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/class-runtime-errors/dynamic-bad-class-array-element-e8001.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/class-runtime-errors/dynamic-bad-class-param-e8001.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/class-runtime-errors/dynamic-bad-imported-class-param-e8001.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/control-flow-errors/error-inside-for-of.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/control-flow-errors/error-inside-while-loop.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/defaults/plan-host-discriminator.deal | js | PROCESS_FAILURE",
        "backend-runtime/descriptors/canonical-sig-mismatch-e8010.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-async-bad.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/host-abi/host-async-bad.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-async-shape-bad.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-async-shape-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-async-shape-value.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-bad-return.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-bad-return.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-class-default-isolation.deal | js | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-class-export.deal | js | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-class-extra-field.deal | js | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-empty-return-bad.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-empty-return-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-export-presence.deal | js | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-invalid-utf8-e8010.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-invalid-utf8-e8010.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-missing-export.deal | js | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-missing-export.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-missing-export.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-nullable-function-param-bad.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/host-abi/host-nullable-function-param-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-nullable-function-return-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-nullable-return-bad.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-nullable-return-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-null-return-bad.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-null-return-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-prewrapped-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-rest-bad.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-surrogate-utf8-e8010.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/host-abi/host-surrogate-utf8-e8010.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/host-abi/host-surrogate-utf8-e8010.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/jsonable/jsonable-tojson-rejects-cyclic-table.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/jsonable/jsonable-tojson-rejects-cyclic-table.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/jsonable/jsonable-tojson-rejects-cyclic-table.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/module-failures/imported-function-explicit-error.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/modules/modid-class-identity.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/modules/modid-class-identity.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/array-negative-write-e8002.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/async-error-code-through-module.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/chained-access-type-error-e8001.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/int-div-zero-e8005.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/json-stringify-function-e8001.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/runtime-errors/json-stringify-function-e8001.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/nested-array-oob-e8001.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/rethrow-across-function-boundary.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/rethrow-across-function-boundary.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/rethrow-preserves-code.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/rethrow-preserves-code.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/runtime-errors/type-mismatch-e8001.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/runtime-errors/type-mismatch-e8001.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime/int-convert-noninteger.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime/int-convert-null.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime/int-convert-range.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/runtime/int-convert-range.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/runtime/number-convert-null.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location/async-error-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location/int32-overflow-source.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/source-location/int32-overflow-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location/int32-overflow-source.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/source-location/int-neg-min-source.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/source-location/int-neg-min-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location/int-neg-min-source.deal | luajit | TRANSCRIPT_MISMATCH",
        "backend-runtime/source-location/json-error-source.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/source-location/json-error-source.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/source-location/module-error-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location/nested-array-oob-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location-precision/class-param-error-source.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/source-location-precision/closure-error-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location-precision/imported-async-error-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location-precision/loop-error-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location-precision/stdlib-error-source.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/source-location-precision/stdlib-error-source.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/console-log-dynamic-nonstring.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/console-log-dynamic-nonstring.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/string-length-dynamic-nonstring.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/string-length-dynamic-nonstring.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/table-keys-dynamic-nontable.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/table-keys-dynamic-nontable.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/stdlib-edge/time-now-millis-positive.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/stdlib/json/json-stringify-bytes-error.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/stdlib/json/json-stringify-bytes-error.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/stdlib/math/int-abs-min-overflow.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/stdlib/math/int-abs-min-overflow.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/stdlib/math/int-abs-min-overflow.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/stdlib/table/keys-nontable-error.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/stdlib/table/keys-nontable-error.deal | luajit | PROCESS_FAILURE",
        "backend-runtime/tables/table-dynamic-read-runtime-error.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/type-system/dynamic-array-element-e8003.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/type-system/dynamic-nonfunction-to-function-e8001.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/type-system/dynamic-return-e8001.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/type-system/dynamic-to-int-param-e8001.deal | js | TRANSCRIPT_MISMATCH",
        "backend-runtime/type-system/dynamic-to-int-param-e8001.deal | jvm | PROCESS_FAILURE",
        "backend-runtime/type-system/dynamic-wrong-to-nullable-e8001.deal | jvm | PROCESS_FAILURE");

    /** Representative pinned first-difference details (the gate's
     * bounded-context reports), asserted verbatim. */
    private static final String JS_E8003_DIVERGENCE_DETAIL =
        "stdout differs at byte 21: expected 0x33, got 0x31; context "
            + "expected \"DE: E8003\\nDEAL_E\", got \"DE: E8001\\nDEAL_E\"";
    private static final String LUA_ADD_OVERFLOW_LINE_DETAIL_PREFIX =
        "stdout differs at byte 165: expected 0x38, got 0x37";
    private static final String JVM_ADD_OVERFLOW_MISSING_COLUMN_PREFIX =
        "the captured DEAL error carries no complete DEALRuntimeError "
            + "field set (code, message, file, line, column are "
            + "mandatory) — the lane cannot serialize the canonical "
            + "snapshot; the JVM runtime error carries no "
            + "column/expected/actual/frames/cause fields yet "
            + "(ISSUE-0276 owns the backend convergence), and the lane "
            + "never fabricates them; captured fields: code=E8004, "
            + "message=int out of safe range, file=Int_add_overflow.java, "
            + "line=20, column=null";

    public static void main(String[] args) throws Exception {
        System.out.println("=== Differential Gate Lanes Corpus Tests "
            + "(ISSUE-0357) ===\n");

        int jobs = Integer.getInteger("deal.test.jobs",
            Runtime.getRuntime().availableProcessors());
        System.out.println("Gate worker bound: " + jobs + " (deal.test.jobs="
            + System.getProperty("deal.test.jobs", "<unset>")
            + ", processors=" + Runtime.getRuntime().availableProcessors()
            + ")");

        fullGateCorpusRun(jobs);
        divergentConsoleStringProbe(jobs);
        printReturnValueDivergenceProbe(jobs);
        invokeMainTwiceDivergenceProbe(jobs);
        reorderedExportsDivergenceProbe(jobs);
        stderrFramingDivergenceProbe(jobs);
        perturbedErrorFieldProbe(jobs);
        pinnedAbsentOptionalProbe(jobs);
        serializationViolationProbe(jobs);
        compilePinPerturbationProbe(jobs);
        secondErrorDiagnosticProbe(jobs);
        divergentSidecarSchemaProbe(jobs);
        laneTimeoutProbe(jobs);

        deleteScratchRoots();

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("FAIL: " + message);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** One scratch corpus: a fresh temp root with one fixture (plus its
     * sidecar when {@code sidecarJson} is non-null). */
    private record ScratchCorpus(Path root, Path file, String corpusPath,
                                 String strippedSource, String sidecarJson) {
        ScratchCorpus {
            Objects.requireNonNull(root, "root must not be null");
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(corpusPath, "corpusPath must not be null");
            Objects.requireNonNull(strippedSource,
                "strippedSource must not be null");
        }
    }

    private static final List<Path> scratchRoots = new ArrayList<>();

    private static ScratchCorpus writeScratchCorpus(String corpusPath,
            String rawSource, String sidecarJson) throws IOException {
        Path root = Files.createTempDirectory("gate_lanes_scratch_");
        scratchRoots.add(root);
        Path file = root.resolve(corpusPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, rawSource);
        if (sidecarJson != null) {
            Path sidecar = root.resolve(corpusPath.substring(0,
                corpusPath.length() - ".deal".length()) + ".expect.json");
            Files.writeString(sidecar, sidecarJson);
        }
        String stripped = deal.test.ConformanceHarnessMetadata
            .stripClassificationHeaders(rawSource);
        return new ScratchCorpus(root, file, corpusPath, stripped,
            sidecarJson);
    }

    private static void deleteScratchRoots() {
        for (Path root : scratchRoots) {
            try (var stream = Files.walk(root)) {
                for (Path path : stream.sorted(Collections.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ignored) {
                // Best-effort scratch cleanup; the temp dirs are OS-scoped.
            }
        }
        scratchRoots.clear();
    }

    /** The three production lanes over one root, with the optional
     * lane-variant replacement for one backend. */
    private static Map<String, Lane> lanes(Path root, String replacedBackend,
            Lane replacement) {
        Map<String, Lane> lanes = new LinkedHashMap<>();
        lanes.put("luajit", "luajit".equals(replacedBackend)
            ? replacement : new LuaLane(root));
        lanes.put("jvm", "jvm".equals(replacedBackend)
            ? replacement : new JvmLane(root));
        lanes.put("js", "js".equals(replacedBackend)
            ? replacement : new JsLane(root));
        return lanes;
    }

    /** Runs the gate over a scratch corpus and returns the run. */
    private static DifferentialGate.GateRun runScratch(Path root,
            Map<String, Lane> lanes, Duration deadline) throws IOException {
        PrintStream capture = new PrintStream(new ByteArrayOutputStream(),
            true, StandardCharsets.UTF_8);
        return DifferentialGate.run(root, lanes, 4, deadline, capture,
            Map.of());
    }

    private static GateDispatcher.CaseVerdict verdictOf(
            DifferentialGate.GateRun run, String fixturePath) {
        for (GateDispatcher.CaseVerdict verdict : run.verdicts()) {
            if (verdict.fixturePath().equals(fixturePath)) {
                return verdict;
            }
        }
        return null;
    }

    private static GateDispatcher.LaneOutcome outcomeOf(
            DifferentialGate.GateRun run, String fixturePath,
            String backend) {
        GateDispatcher.CaseVerdict verdict = verdictOf(run, fixturePath);
        if (verdict == null) {
            return null;
        }
        for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
            if (outcome.backend().equals(backend)) {
                return outcome;
            }
        }
        return null;
    }

    /** The {@code fixture | backend | class} triple of one outcome. */
    private static String triple(String fixturePath,
            GateDispatcher.LaneOutcome outcome) {
        return fixturePath + " | " + outcome.backend() + " | "
            + outcome.mismatch().get().clazz();
    }

    private static String stdoutOf(LaneExecution execution) {
        return execution instanceof LaneExecution.Executed executed
            ? new String(executed.stdout(), StandardCharsets.UTF_8)
            : "<not executed>";
    }

    private static final String HEADER_RUNTIME_OK =
        "// @spec: Control flow — if-else\n"
        + "// @description: scratch differential gate probe\n"
        + "// @expected: runtime-ok\n"
        + "// @features: scratch\n"
        + "\n";

    private static final String HEADER_RUNTIME_ERROR_CODE =
        "// @spec: Error handling — try-catch\n"
        + "// @description: scratch differential gate probe\n"
        + "// @expected: runtime-error %s\n"
        + "// @features: scratch\n"
        + "\n";

    private static String uniformSidecar(String stdout) {
        return "{\n"
            + "  \"version\": 1,\n"
            + "  \"backends\": [\"luajit\", \"jvm\", \"js\"],\n"
            + "  \"expected\": {\n"
            + "    \"mode\": \"runtime-ok\",\n"
            + "    \"transcript\": { \"stdout\": " + jsonString(stdout)
            + ", \"stderr\": \"\" },\n"
            + "    \"exitCode\": 0\n"
            + "  }\n"
            + "}\n";
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // =========================================================================
    // Full gate run over the real corpus (Verification 1 + 8, G8 accounting)
    // =========================================================================

    private static void fullGateCorpusRun(int jobs) throws IOException {
        System.out.println("-- Full three-lane gate over the real corpus --");
        long start = System.nanoTime();
        JvmLane jvm = new JvmLane(CORPUS_ROOT);
        Map<String, Lane> lanes = Map.of(
            "luajit", new LuaLane(CORPUS_ROOT),
            "jvm", jvm,
            "js", new JsLane(CORPUS_ROOT));
        PrintStream capture = new PrintStream(new ByteArrayOutputStream(),
            true, StandardCharsets.UTF_8);
        DifferentialGate.GateRun run = DifferentialGate.run(CORPUS_ROOT,
            lanes, jobs, FULL_RUN_DEADLINE, capture,
            Map.of("jvm", jvm.preFlipSkipRegistry()));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        check(elapsedMillis < FULL_RUN_DEADLINE.toMillis() * 4,
            "the full three-lane gate completes inside the budget "
                + "envelope (elapsed " + elapsedMillis + " ms)");
        check(run.fixtures().size() == TOTAL_FIXTURES,
            "the full run discovers " + TOTAL_FIXTURES + " fixtures, got "
                + run.fixtures().size());
        check(run.classificationFailures().isEmpty(),
            "zero classification failures over the real corpus: "
                + run.classificationFailures());
        check(run.frontendCompiled() == FRONTEND_COMPILED,
            "the frontend corpus executes backend-neutral (" + FRONTEND_COMPILED
                + " compile-ok/compile-error fixtures), got "
                + run.frontendCompiled());
        check(run.compileDiagnosticComparisons() == COMPILE_PINS,
            "the " + COMPILE_PINS + " Compile Diagnostic comparisons run");
        check(run.failures().stream().noneMatch(f ->
                "frontend-compile".equals(f.kind())
                    || "compile-diagnostic".equals(f.kind())
                    || "stale known-fail".equals(f.kind())
                    || "stale skip-registry".equals(f.kind())),
            "zero frontend-compile, compile-diagnostic, stale known-fail, "
                + "and stale skip-registry failures, got: " + run.failures());
        check(run.runtimeCasesDispatched() == RUNTIME_CASES
                && run.runtimeCasesDeferred() == 0,
            "every runtime case dispatches exactly once ("
                + RUNTIME_CASES + " dispatched, 0 deferred), got "
                + run.runtimeCasesDispatched() + "/"
                + run.runtimeCasesDeferred());
        check(run.verdicts().size() == RUNTIME_CASES,
            "one verdict per dispatched case, got " + run.verdicts().size());

        // Pre-flip accounting (G8): Skipped/KnownFailures counters and
        // the exact tracked non-fatal set.
        check(run.skipped() == 0,
            "the Skipped counter is zero");
        check(run.knownFailuresTracked() == KNOWN_FAILURES_TRACKED,
            "the KnownFailures counter tracks exactly "
                + KNOWN_FAILURES_TRACKED + " fixture, got "
                + run.knownFailuresTracked());
        check(run.skipRegistryTracked() == SKIP_REGISTRY_ENTRIES,
            "the skip registry tracks exactly " + SKIP_REGISTRY_ENTRIES
                + " non-fatal outcomes, got " + run.skipRegistryTracked());
        Set<String> registryPaths = new java.util.TreeSet<>();
        for (DifferentialGate.PreFlipSkipEntry entry
                : jvm.preFlipSkipRegistry().entries()) {
            registryPaths.add(entry.corpusPath());
        }
        check(registryPaths.equals(new java.util.TreeSet<>(TRACKED_REGISTRY)),
            "the live registry carries exactly the pinned " 
                + SKIP_REGISTRY_ENTRIES + " entries");
        check(jvm.registryDefects().isEmpty(),
            "the registry validates cleanly against the real corpus: "
                + jvm.registryDefects());

        // The designated converged subset passes byte-exact on all
        // three lanes (criterion (a)).
        for (String fixturePath : CONVERGED_SUBSET) {
            GateDispatcher.CaseVerdict verdict = verdictOf(run, fixturePath);
            check(verdict != null && verdict.passed(),
                fixturePath + " passes on all three lanes byte-exact, "
                    + "got: " + verdict);
        }

        // The failure set is exactly the tracked non-fatal set plus the
        // enumerated differential failures (criterion (b)): every
        // failing outcome is either a tracked registry outcome (jvm
        // only, never a gate failure) or one of the pinned
        // differential-failure triples, and the enumeration matches
        // exactly — nothing hidden, nothing skipped.
        Set<String> trackedOutcomes = new LinkedHashSet<>();
        Set<String> differentialOutcomes = new LinkedHashSet<>();
        for (GateDispatcher.CaseVerdict verdict : run.verdicts()) {
            for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                if (outcome.passed()) {
                    continue;
                }
                check(outcome.mismatch().isPresent()
                        && outcome.mismatch().get().detail() != null
                        && !outcome.mismatch().get().detail().isEmpty(),
                    verdict.fixturePath() + " " + outcome.backend()
                        + ": every failing outcome carries a bounded "
                        + "detail, got: " + outcome.mismatch());
                check(outcome.mismatch().get().clazz()
                        != MismatchClass.TOOL_MISSING
                        && !outcome.mismatch().get().clazz().toString()
                            .contains("SKIP"),
                    verdict.fixturePath() + " " + outcome.backend()
                        + ": no skip-class outcome exists anywhere, got "
                        + outcome.mismatch().get().clazz());
                if ("jvm".equals(outcome.backend())
                        && TRACKED_REGISTRY.contains(
                            verdict.fixturePath())) {
                    trackedOutcomes.add(verdict.fixturePath());
                } else {
                    differentialOutcomes.add(
                        triple(verdict.fixturePath(), outcome));
                }
            }
        }
        check(trackedOutcomes.equals(TRACKED_REGISTRY),
            "the tracked non-fatal set is exactly the pinned registry "
                + "set (" + trackedOutcomes.size() + "), got "
                + (TRACKED_REGISTRY.size() == trackedOutcomes.size()
                    ? "size match" : "size mismatch: "
                        + trackedOutcomes.size()));
        // Every differential failure names the first differing
        // byte/field in its bounded detail (criterion (b)).
        int firstDifferenceNamed = 0;
        for (GateDispatcher.CaseVerdict verdict : run.verdicts()) {
            for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                if (outcome.passed()) {
                    continue;
                }
                GateMismatch mismatch = outcome.mismatch().get();
                boolean namesFirstDifference = switch (mismatch.clazz()) {
                    case TRANSCRIPT_MISMATCH -> mismatch.detail().contains(
                        "differs at byte");
                    case ERROR_SNAPSHOT_MISMATCH -> mismatch.detail().contains(
                        "error.") || mismatch.detail().contains(
                            "optional error field") || mismatch.detail()
                            .contains("framing");
                    case COMPILE_DIAGNOSTIC_MISMATCH -> mismatch.detail()
                        .contains("diagnostic.") || mismatch.detail()
                        .contains("exactly one error diagnostic");
                    case EXIT_CODE_MISMATCH -> mismatch.detail().contains(
                        "exitCode");
                    case COMPILE_REJECT_MISMATCH -> mismatch.detail().contains(
                        "diagnostic");
                    default -> !mismatch.detail().isEmpty();
                };
                check(namesFirstDifference,
                    verdict.fixturePath() + " " + outcome.backend()
                        + ": the " + mismatch.clazz() + " detail names the "
                        + "first differing byte/field, got: "
                        + mismatch.detail());
                if (namesFirstDifference) {
                    firstDifferenceNamed++;
                }
            }
        }
        check(firstDifferenceNamed == differentialOutcomes.size()
                + trackedOutcomes.size(),
            "every failing outcome names the first differing byte/field ("
                + firstDifferenceNamed + " of "
                + (differentialOutcomes.size() + trackedOutcomes.size())
                + ")");

        check(differentialOutcomes.equals(DIFFERENTIAL_FAILURES),
            "the differential failure set is exactly the pinned "
                + "enumeration (" + differentialOutcomes.size()
                + " entries): missing "
                + (DIFFERENTIAL_FAILURES.stream()
                    .filter(f -> !differentialOutcomes.contains(f))
                    .toList().size()) + ", extra "
                + (differentialOutcomes.stream()
                    .filter(f -> !DIFFERENTIAL_FAILURES.contains(f))
                    .toList().size()));

        // Every differential failure appears in the gate's failure list
        // naming fixture, backend, and class (criterion (b)); no tracked
        // outcome appears there.
        int laneFailures = (int) run.failures().stream()
            .filter(f -> "lane".equals(f.kind())).count();
        int infrastructureFailures = (int) run.failures().stream()
            .filter(f -> "infrastructure".equals(f.kind())).count();
        check(laneFailures + infrastructureFailures
                == differentialOutcomes.size(),
            "the gate records every differential failure ("
                + differentialOutcomes.size() + " = " + laneFailures
                + " lane + " + infrastructureFailures
                + " infrastructure), got: " + run.failures().stream()
                    .filter(f -> "lane".equals(f.kind())
                        || "infrastructure".equals(f.kind()))
                    .map(DifferentialGate.GateFailure::message)
                    .toList());
        for (DifferentialGate.GateFailure failure : run.failures()) {
            if (!"lane".equals(failure.kind())
                    && !"infrastructure".equals(failure.kind())) {
                continue;
            }
            check(failure.subject().matches(
                    "(luajit|jvm|js) backend-runtime/.+\\.deal"),
                "every differential failure names backend and fixture: "
                    + failure.subject());
        }

        // The per-backend counters and the closed mismatch classes.
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            int[] counts = run.perBackend().get(backend);
            check(counts[0] == PER_BACKEND.get(backend)[0]
                    && counts[1] == PER_BACKEND.get(backend)[1],
                backend + " counters are " + PER_BACKEND.get(backend)[0]
                    + " passed / " + PER_BACKEND.get(backend)[1]
                    + " failed, got " + counts[0] + " / " + counts[1]);
        }
        for (String triplePin : DIFFERENTIAL_FAILURES) {
            check(triplePin.endsWith("| TRANSCRIPT_MISMATCH")
                    || triplePin.endsWith("| PROCESS_FAILURE"),
                "the pinned class of " + triplePin
                    + " is a closed mismatch class");
        }

        // The shared time fixture's js leg passes after the
        // disposition-application unit (ISSUE-0536 remediation): the
        // lane suppresses the captured call-site span against the
        // sanctioned span-less sidecar, so the transcript matches the
        // pinned expectation field-exactly — the JS half of the
        // combined-behavior proof on the differential gate.
        GateDispatcher.CaseVerdict timeVerdict = verdictOf(run,
            "backend-runtime/stdlib-edge/time-now-millis-positive.deal");
        check(timeVerdict != null,
            "the time fixture is dispatched, got: " + timeVerdict);
        GateDispatcher.LaneOutcome timeJs = timeVerdict == null ? null
            : timeVerdict.outcomes().stream()
                .filter(o -> "js".equals(o.backend())).findFirst()
                .orElse(null);
        check(timeJs != null && timeJs.passed(),
            "the time fixture's js leg passes byte-exact against the "
                + "span-less sidecar, got: " + timeJs);
        if (timeJs != null && timeJs.passed()) {
            check(timeJs.mismatch().isEmpty(),
                "the time fixture's js leg carries no mismatch, got: "
                    + timeJs.mismatch());
        }

        // Representative first-difference spot pins (the gate's bounded
        // reports naming the first differing byte / field).
        DifferentialGate.GateFailure jsDivergence = run.failures().stream()
            .filter(f -> f.subject().equals("js "
                + "backend-runtime/type-system/dynamic-array-element-e8003.deal"))
            .findFirst().orElse(null);
        check(jsDivergence != null
                && jsDivergence.detail().equals(JS_E8003_DIVERGENCE_DETAIL),
            "the js E8003 divergence pins the exact first differing byte, "
                + "got: " + jsDivergence);
        DifferentialGate.GateFailure luaLine = run.failures().stream()
            .filter(f -> f.subject().equals("luajit "
                + "backend-runtime/arithmetic/int-add-overflow.deal"))
            .findFirst().orElse(null);
        check(luaLine != null
                && luaLine.detail().startsWith(
                    LUA_ADD_OVERFLOW_LINE_DETAIL_PREFIX),
            "the luajit int-add-overflow divergence pins the exact first "
                + "differing byte, got: " + luaLine);
        DifferentialGate.GateFailure jvmColumn = run.failures().stream()
            .filter(f -> f.subject().equals("jvm "
                + "backend-runtime/arithmetic/int-add-overflow.deal"))
            .findFirst().orElse(null);
        check(jvmColumn != null
                && jvmColumn.detail().startsWith(
                    JVM_ADD_OVERFLOW_MISSING_COLUMN_PREFIX),
            "the jvm int-add-overflow infrastructure outcome names the "
                + "missing field, got: " + jvmColumn);

        // The gate verdict is FAIL pre-flip (the staged failure set is
        // exactly the pinned enumeration) and the summary prints the
        // zero-skip counters.
        check(!run.ok(),
            "the pre-flip gate verdict is FAIL (the staged failure set "
                + "is visible, never greenwashed)");
        System.out.println("    full run: " + elapsedMillis + " ms, "
            + run.verdicts().size() + " verdicts, "
            + differentialOutcomes.size() + " differential failures, "
            + trackedOutcomes.size() + " tracked non-fatal");
    }

    // =========================================================================
    // Experiment 1: a divergent console string on exactly one backend
    // (Verification 1)
    // =========================================================================

    /** TEST DOUBLE — a scratch Lua lane perturbing one console byte of
     * the captured transcript (the controlled single-backend divergence;
     * flagged as a double, the other two lanes stay production). */
    private static final class ConsolePerturbingLuaLane extends LuaLane {
        ConsolePerturbingLuaLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        public LaneExecution execute(LaneCase laneCase) throws Exception {
            LaneExecution base = super.execute(laneCase);
            if (base instanceof LaneExecution.Executed executed) {
                byte[] stdout = new String(executed.stdout(),
                    StandardCharsets.UTF_8).replace("beta", "gamma")
                    .getBytes(StandardCharsets.UTF_8);
                return new LaneExecution.Executed(stdout, executed.stderr(),
                    executed.exitCode());
            }
            return base;
        }
    }

    private static final String DIVERGENCE_PROBE_SOURCE =
        "import * as console from \"std/console\"\n"
        + "\n"
        + "export function first_export(): null {\n"
        + "  console.log(\"alpha\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function second_export(): null {\n"
        + "  console.log(\"beta\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    private static void divergentConsoleStringProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 1: divergent console string on "
            + "exactly one backend --");
        String corpusPath = "backend-runtime/scratch/divergence-probe.deal";
        ScratchCorpus scratch = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + DIVERGENCE_PROBE_SOURCE,
            uniformSidecar("alpha\nbeta\n"));
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), "luajit",
                new ConsolePerturbingLuaLane(scratch.root())),
            Duration.ofSeconds(60));
        check(!run.ok() && run.failures().size() == 1,
            "the divergent case fails with exactly one gate failure, got: "
                + run.failures());
        GateDispatcher.LaneOutcome luajit = outcomeOf(run, corpusPath,
            "luajit");
        check(luajit != null && !luajit.passed()
                && luajit.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && luajit.mismatch().get().subject().equals("luajit")
                && luajit.mismatch().get().detail().contains(
                    "stdout differs at byte 6: expected 0x62, got 0x67"),
            "the divergence fails exactly the luajit lane with the first "
                + "differing byte, got: " + luajit);
        GateDispatcher.LaneOutcome jvm = outcomeOf(run, corpusPath, "jvm");
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(jvm != null && jvm.passed() && js != null && js.passed(),
            "the jvm and js lanes match byte-exact (only the perturbed "
                + "backend diverges), got jvm=" + jvm + " js=" + js);
        DifferentialGate.GateFailure failure = run.failures().get(0);
        check("lane".equals(failure.kind())
                && failure.subject().equals("luajit " + corpusPath),
            "the gate failure names backend, fixture, and class, got: "
                + failure);
    }

    // =========================================================================
    // Experiment 2: lane-contract divergences (Verification 4)
    // =========================================================================

    private static final String VALUE_PROBE_SOURCE =
        "export function test_value(): int {\n"
        + "  return 42;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    /** TEST DOUBLE — a scratch JS lane whose runner prints every
     * invocation's non-null return value (the G4.4 divergence). */
    private static final class PrintReturnValueLane extends JsLane {
        PrintReturnValueLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected String buildRunner(String entryModule,
                List<String> orderedZeroArityExports) {
            return super.runnerPreamble(entryModule,
                    orderedZeroArityExports)
                + "const $rt = require(\"./deal/runtime\");\n"
                + "(async () => {\n"
                + "  const $mod = require(\"./" + entryModule + "\");\n"
                + "  if ($mod.main && $mod.main.$kind === \"function\") "
                + "{ $mod.main.$f(); }\n"
                + "  for (const $k of $zeroAry) {\n"
                + "    const $v = $mod[$k];\n"
                + "    if ($v && $v.$kind === \"function\") {\n"
                + "      const $r = await $v.$f();\n"
                + "      if ($r !== null && $r !== $rt.undefined) {\n"
                + "        console.log($r);\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "})().catch(($e) => { process.exitCode = 1; });\n";
        }
    }

    private static void printReturnValueDivergenceProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 2a: a lane printing a return "
            + "value --");
        String corpusPath = "backend-runtime/scratch/value-probe.deal";
        ScratchCorpus scratch = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + VALUE_PROBE_SOURCE, uniformSidecar(""));
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), "js",
                new PrintReturnValueLane(scratch.root())),
            Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && js.mismatch().get().subject().equals("js")
                && js.mismatch().get().detail().contains(
                    "stdout differs at byte 0"),
            "a lane printing a return value fails the pinned transcript "
                + "(TRANSCRIPT_MISMATCH naming the backend and the first "
                + "differing byte), got: " + js);
        check(outcomeOf(run, corpusPath, "luajit").passed()
                && outcomeOf(run, corpusPath, "jvm").passed(),
            "the luajit and jvm lanes discard results and pass");
    }

    private static final String MAIN_PROBE_SOURCE =
        "import * as console from \"std/console\"\n"
        + "\n"
        + "export function test_nothing(): null {\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  console.log(\"main\");\n"
        + "  return null;\n"
        + "}\n";

    private static void invokeMainTwiceDivergenceProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 2b: a lane invoking main twice --");
        String corpusPath = "backend-runtime/scratch/main-probe.deal";
        ScratchCorpus scratch = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + MAIN_PROBE_SOURCE, uniformSidecar("main\n"));
        JsLane doubleMainLane = new JsLane(scratch.root()) {
            @Override
            protected String buildRunner(String entryModule,
                    List<String> orderedZeroArityExports) {
                return super.buildRunner(entryModule,
                    orderedZeroArityExports).replace(
                        "  for (const $k of $zeroAry) {",
                        "  if ($mod.main && $mod.main.$kind"
                            + " === \"function\") { $mod.main.$f(); }\n"
                            + "  for (const $k of $zeroAry) {");
            }
        };
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), "js", doubleMainLane),
            Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && js.mismatch().get().detail().contains(
                    "stdout differs at byte 5: expected end of stream, "
                        + "got byte 0x6D"),
            "a lane invoking main twice fails the pinned \"main\\n\" "
                + "transcript naming the backend and the first differing "
                + "byte, got: " + js);
        check(outcomeOf(run, corpusPath, "luajit").passed()
                && outcomeOf(run, corpusPath, "jvm").passed(),
            "the luajit and jvm lanes invoke main exactly once and pass");
    }

    private static final String ORDER_PROBE_SOURCE =
        "import * as console from \"std/console\"\n"
        + "\n"
        + "export function first_export(): null {\n"
        + "  console.log(\"first\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function second_export(): null {\n"
        + "  console.log(\"second\");\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    /** TEST DOUBLE — a scratch JS lane whose runner invokes the exports
     * in reverse declaration order (the G4.4 divergence). */
    private static final class ReorderedExportsLane extends JsLane {
        ReorderedExportsLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected String buildRunner(String entryModule,
                List<String> orderedZeroArityExports) {
            List<String> reversed = new ArrayList<>(orderedZeroArityExports);
            Collections.reverse(reversed);
            return super.buildRunner(entryModule, reversed);
        }
    }

    private static void reorderedExportsDivergenceProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 2c: a lane reordering export "
            + "invocation --");
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchCorpus scratch = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + ORDER_PROBE_SOURCE,
            uniformSidecar("first\nsecond\n"));
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), "js",
                new ReorderedExportsLane(scratch.root())),
            Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && js.mismatch().get().detail().contains(
                    "stdout differs at byte 0: expected 0x66, got 0x73"),
            "a lane reordering export invocation fails the declaration-"
                + "order transcript naming the backend and the first "
                + "differing byte, got: " + js);
        check(outcomeOf(run, corpusPath, "luajit").passed()
                && outcomeOf(run, corpusPath, "jvm").passed(),
            "the luajit and jvm lanes invoke in declaration order and "
                + "pass");
    }

    private static void stderrFramingDivergenceProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 2d: a lane writing framing to "
            + "stderr --");
        String corpusPath = "backend-runtime/scratch/order-probe.deal";
        ScratchCorpus scratch = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + ORDER_PROBE_SOURCE,
            uniformSidecar("first\nsecond\n"));
        JsLane stderrFramingLane = new JsLane(scratch.root()) {
            @Override
            protected String buildRunner(String entryModule,
                    List<String> orderedZeroArityExports) {
                return super.buildRunner(entryModule,
                    orderedZeroArityExports)
                    + "\nprocess.stderr.write("
                    + "\"DEAL_ERROR_CODE: LANE_LEAK\\n\");\n";
            }
        };
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), "js", stderrFramingLane),
            Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && js.mismatch().get().detail().contains(
                    "stderr differs at byte 0"),
            "a lane writing framing to stderr fails the pinned empty "
                + "stderr naming the backend and the first differing "
                + "byte, got: " + js);
        check(outcomeOf(run, corpusPath, "luajit").passed()
                && outcomeOf(run, corpusPath, "jvm").passed(),
            "the luajit and jvm lanes keep framing on stdout and pass");
    }

    // =========================================================================
    // Experiment 3: error-snapshot enforcement (Verification 5)
    // =========================================================================

    /** TEST DOUBLE — a scratch JS lane whose deployment lookup rebases
     * the captured line by one extra header line (perturbing exactly the
     * snapshot's line field). */
    private static final class LinePerturbingJsLane extends JsLane {
        LinePerturbingJsLane(Path conformanceRoot) {
            super(conformanceRoot);
        }

        @Override
        protected JsLane.DeploymentEntry deploymentEntryFor(
                String capturedFile, JsLane.JsCompilation compilation) {
            JsLane.DeploymentEntry entry =
                super.deploymentEntryFor(capturedFile, compilation);
            if (entry == null) {
                return null;
            }
            return new JsLane.DeploymentEntry(entry.corpusPath(),
                entry.headerLinesStripped() + 1);
        }
    }

    private static final String FRAME_PROBE_SOURCE =
        "export function test_frame_probe(): null {\n"
        + "  throw { code: \"FRAME_PROBE\", message: \"frame probe\" };\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    /** The canonical runtime-error sidecar of the scratch frame probe
     * (raw-file coordinates: the throw sits at raw line 7, column 3). */
    private static String frameProbeSidecar(String snapshotJson,
            String errorJson) {
        return "{\n"
            + "  \"version\": 1,\n"
            + "  \"backends\": [\"luajit\", \"jvm\", \"js\"],\n"
            + "  \"expected\": {\n"
            + "    \"mode\": \"runtime-error\",\n"
            + "    \"transcript\": {\n"
            + "      \"stdout\": \"DEAL_ERROR_CODE: FRAME_PROBE\\n"
            + "DEAL_ERROR_SNAPSHOT: " + snapshotJson + "\\n\",\n"
            + "      \"stderr\": \"\"\n"
            + "    },\n"
            + "    \"exitCode\": 1,\n"
            + "    \"error\": " + errorJson + "\n"
            + "  }\n"
            + "}\n";
    }

    private static final String CANONICAL_SNAPSHOT =
        "{\\\"code\\\":\\\"FRAME_PROBE\\\",\\\"message\\\":"
            + "\\\"frame probe\\\",\\\"sourceFile\\\":"
            + "\\\"backend-runtime/scratch/frame-probe.deal\\\","
            + "\\\"line\\\":7,\\\"column\\\":3}";
    private static final String CANONICAL_ERROR =
        "{ \"code\": \"FRAME_PROBE\", \"message\": "
            + "\"frame probe\", \"sourceFile\": "
            + "\"backend-runtime/scratch/frame-probe.deal\", "
            + "\"line\": 7, \"column\": 3 }";

    private static final String JVM_FRAME_PROBE_GAP_PREFIX =
        "the captured DEAL error carries no complete DEALRuntimeError "
            + "field set (code, message, file, line, column are "
            + "mandatory) — the lane cannot serialize the canonical "
            + "snapshot; the JVM runtime error carries no "
            + "column/expected/actual/frames/cause fields yet "
            + "(ISSUE-0276 owns the backend convergence), and the lane "
            + "never fabricates them";

    private static void perturbedErrorFieldProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 3a: a sidecar perturbing one "
            + "error field --");
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        String raw = String.format(HEADER_RUNTIME_ERROR_CODE, "FRAME_PROBE")
            + FRAME_PROBE_SOURCE;
        // The transcript stays canonical and the code stays the pinned
        // FRAME_PROBE (the classification cross-check); the error
        // object's message is perturbed — schema-valid, so the
        // comparison reaches the field-exact snapshot check.
        String sidecar = frameProbeSidecar(CANONICAL_SNAPSHOT,
            "{ \"code\": \"FRAME_PROBE\", \"message\": "
                + "\"frame probe X\", \"sourceFile\": "
                + "\"backend-runtime/scratch/frame-probe.deal\", "
                + "\"line\": 7, \"column\": 3 }");
        ScratchCorpus scratch = writeScratchCorpus(corpusPath, raw, sidecar);
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), null, null), Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome luajit = outcomeOf(run, corpusPath,
            "luajit");
        check(luajit != null && !luajit.passed()
                && luajit.mismatch().get().clazz()
                    == MismatchClass.ERROR_SNAPSHOT_MISMATCH
                && luajit.mismatch().get().subject().equals("luajit")
                && luajit.mismatch().get().detail().contains(
                    "error.message must be \"frame probe X\", got "
                        + "\"frame probe\""),
            "the perturbed message fails the luajit lane with "
                + "ERROR_SNAPSHOT_MISMATCH naming the field, got: "
                + luajit);
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.ERROR_SNAPSHOT_MISMATCH
                && js.mismatch().get().detail().contains(
                    "error.message must be"),
            "the perturbed message fails the js lane with "
                + "ERROR_SNAPSHOT_MISMATCH naming the field, got: " + js);
        GateDispatcher.LaneOutcome jvm = outcomeOf(run, corpusPath, "jvm");
        check(jvm != null && !jvm.passed()
                && jvm.mismatch().get().clazz()
                    == MismatchClass.PROCESS_FAILURE
                && jvm.mismatch().get().detail().startsWith(
                    JVM_FRAME_PROBE_GAP_PREFIX),
            "the jvm lane reports the documented pre-flip column gap "
                + "honestly (never fabricates), got: " + jvm);

        // The "on one backend" form (Verification 5): a flagged lane
        // double rebases exactly the captured line by one on the js
        // lane — the consistent sidecar then fails only that backend's
        // byte comparison naming the first differing byte.
        String consistentSidecar = frameProbeSidecar(CANONICAL_SNAPSHOT,
            CANONICAL_ERROR);
        ScratchCorpus scratchLane = writeScratchCorpus(corpusPath, raw,
            consistentSidecar);
        DifferentialGate.GateRun runLane = runScratch(scratchLane.root(),
            lanes(scratchLane.root(), "js",
                new LinePerturbingJsLane(scratchLane.root())),
            Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome perturbedJs = outcomeOf(runLane, corpusPath,
            "js");
        check(perturbedJs != null && !perturbedJs.passed()
                && perturbedJs.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && perturbedJs.mismatch().get().subject().equals("js")
                && perturbedJs.mismatch().get().detail().contains(
                    "stdout differs at byte"),
            "a lane perturbing one snapshot field on one backend fails "
                + "exactly that backend naming the first differing byte, "
                + "got: " + perturbedJs);
        check(outcomeOf(runLane, corpusPath, "luajit").passed(),
            "the unperturbed luajit lane passes the consistent sidecar");
    }

    private static void pinnedAbsentOptionalProbe(int jobs)
            throws Exception {
        System.out.println("-- Experiment 3b: a sidecar pinning an absent "
            + "optional field --");
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        String raw = String.format(HEADER_RUNTIME_ERROR_CODE, "FRAME_PROBE")
            + FRAME_PROBE_SOURCE;
        // frames=2 pinned in the transcript and the error object; the
        // real captured error carries none and the lane never fabricates.
        String snapshotWithFrames = CANONICAL_SNAPSHOT.substring(0,
            CANONICAL_SNAPSHOT.length() - 1)
                + ",\\\"frames\\\":2}";
        String sidecar = frameProbeSidecar(snapshotWithFrames,
            "{ \"code\": \"FRAME_PROBE\", \"message\": "
                + "\"frame probe\", \"sourceFile\": "
                + "\"backend-runtime/scratch/frame-probe.deal\", "
                + "\"line\": 7, \"column\": 3, "
                + "\"frames\": 2 }");
        ScratchCorpus scratch = writeScratchCorpus(corpusPath, raw, sidecar);
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), null, null), Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome luajit = outcomeOf(run, corpusPath,
            "luajit");
        check(luajit != null && !luajit.passed()
                && luajit.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && luajit.mismatch().get().detail().contains(
                    "expected 0x2C, got 0x7D"),
            "the frames-pinned sidecar fails honestly on the luajit lane "
                + "(the emitted snapshot omits frames; the first "
                + "differing byte is the pinned comma vs the emitted "
                + "brace), got: " + luajit);
        check(!stdoutOf(luajitLaneExecution(scratch.root(), corpusPath))
                .contains("frames"),
            "the luajit lane never fabricates the frames field");
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH,
            "the frames-pinned sidecar fails honestly on the js lane, "
                + "got: " + js);
        GateDispatcher.LaneOutcome jvm = outcomeOf(run, corpusPath, "jvm");
        check(jvm != null && !jvm.passed()
                && jvm.mismatch().get().clazz()
                    == MismatchClass.PROCESS_FAILURE,
            "the jvm lane reports the documented pre-flip column gap, "
                + "got: " + jvm);
    }

    private static void serializationViolationProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 3c: a snapshot violating the "
            + "canonical serialization --");
        String corpusPath = "backend-runtime/scratch/frame-probe.deal";
        String raw = String.format(HEADER_RUNTIME_ERROR_CODE, "FRAME_PROBE")
            + FRAME_PROBE_SOURCE;
        // The transcript's snapshot reorders the canonical keys — a
        // serialization violation the byte comparison catches.
        String violatedSnapshot =
            "{\\\"message\\\":\\\"frame probe\\\",\\\"code\\\":"
                + "\\\"FRAME_PROBE\\\",\\\"sourceFile\\\":"
                + "\\\"backend-runtime/scratch/frame-probe.deal\\\","
                + "\\\"line\\\":7,\\\"column\\\":3}";
        String sidecar = frameProbeSidecar(violatedSnapshot,
            CANONICAL_ERROR);
        ScratchCorpus scratch = writeScratchCorpus(corpusPath, raw, sidecar);
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), null, null), Duration.ofSeconds(60));
        GateDispatcher.LaneOutcome luajit = outcomeOf(run, corpusPath,
            "luajit");
        check(luajit != null && !luajit.passed()
                && luajit.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH
                && luajit.mismatch().get().detail().contains(
                    "stdout differs at byte"),
            "the serialization violation fails the byte comparison "
                + "naming the backend and the first differing byte, got: "
                + luajit);
        GateDispatcher.LaneOutcome js = outcomeOf(run, corpusPath, "js");
        check(js != null && !js.passed()
                && js.mismatch().get().clazz()
                    == MismatchClass.TRANSCRIPT_MISMATCH,
            "the serialization violation fails the js lane's byte "
                + "comparison, got: " + js);
        GateDispatcher.LaneOutcome jvm = outcomeOf(run, corpusPath, "jvm");
        check(jvm != null && !jvm.passed()
                && jvm.mismatch().get().clazz()
                    == MismatchClass.PROCESS_FAILURE,
            "the jvm lane reports the documented pre-flip column gap, "
                + "got: " + jvm);
    }

    /** Executes the scratch frame probe on the production luajit lane
     * and returns the captured execution (used to prove the lane never
     * fabricates the frames field). */
    private static LaneExecution luajitLaneExecution(Path root,
            String corpusPath) throws Exception {
        CorpusDiscovery.DiscoveryResult discovery =
            CorpusDiscovery.discover(root);
        CorpusDiscovery.Fixture fixture =
            discovery.corpusByPath().get(corpusPath);
        SidecarGateLoader.LoadResult load = SidecarGateLoader.load(fixture,
            discovery.corpusByPath(), discovery.corpusModuleIndex(), root);
        return new LuaLane(root).execute(new LaneCase(corpusPath, "luajit",
            fixture.file(),
            CorpusDiscovery.compilationSet(fixture, discovery.corpusByPath(),
                root),
            load.runtime().get().expectationFor("luajit")));
    }

    // =========================================================================
    // Experiment 4: compile pins (Verification 7)
    // =========================================================================

    private static final String PIN_PROBE_RAW =
        "// @spec: Diagnostics\n"
        + "// @description: scratch compile pin probe\n"
        + "// @expected: compile-error %s\n"
        + "// @features: scratch\n"
        + "\n"
        + "export function main(): null {\n"
        + "  let x: int = \"nope\";\n"
        + "  return null;\n"
        + "}\n";

    private static String compilePinSidecar(String code, String line,
            String column, String message) {
        return "{\n"
            + "  \"version\": 1,\n"
            + "  \"mode\": \"compile-error\",\n"
            + "  \"diagnostic\": { \"code\": \"" + code + "\", \"line\": "
            + line + ", \"column\": " + column + ", \"message\": "
            + jsonString(message) + " }\n"
            + "}\n";
    }

    private static void compilePinPerturbationProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 4a: perturbed Compile "
            + "Expectation Sidecar fields --");
        // The real frontend emits E3001 at raw line 7, column 16 with
        // the exact message below; each variant perturbs exactly one pin
        // field and must fail COMPILE_DIAGNOSTIC_MISMATCH naming the
        // fixture and the first differing field.
        checkCompilePinVariant("E9999", "E9999", "7", "16",
            "Cannot assign string to int",
            "diagnostic.code must be \"E9999\", got \"E3001\"",
            "code");
        checkCompilePinVariant("E3001", "E3001", "8", "16",
            "Cannot assign string to int",
            "diagnostic.line must be 8, got 7", "line");
        checkCompilePinVariant("E3001", "E3001", "7", "17",
            "Cannot assign string to int",
            "diagnostic.column must be 17, got 16", "column");
        checkCompilePinVariant("E3001", "E3001", "7", "16",
            "Cannot assign int to string",
            "diagnostic.message must be \"Cannot assign int to string\", "
                + "got \"Cannot assign string to int\"",
            "message");
    }

    private static void checkCompilePinVariant(String expectedCode,
            String pinCode, String pinLine, String pinColumn,
            String pinMessage, String expectedDetail, String field)
            throws IOException {
        String corpusPath = "frontend/scratch/pin-probe-" + field + ".deal";
        // The pin's code must equal the fixture's @expected code (schema
        // cross-check), so the code variant flips both together.
        String raw = String.format(PIN_PROBE_RAW, expectedCode);
        String sidecar = compilePinSidecar(pinCode, pinLine, pinColumn,
            pinMessage);
        ScratchCorpus scratch = writeScratchCorpus(corpusPath, raw, sidecar);
        DifferentialGate.GateRun run = runScratch(scratch.root(), Map.of(),
            Duration.ofSeconds(60));
        check(run.classificationFailures().isEmpty(),
            "the perturbed pin sidecar stays schema-valid (field: "
                + field + "), got: " + run.classificationFailures());
        check(run.failures().stream()
                .anyMatch(f -> "compile-diagnostic".equals(f.kind())
                    && f.subject().equals(corpusPath)
                    && f.detail().equals(expectedDetail)),
            "the perturbed " + field + " pin fails "
                + "COMPILE_DIAGNOSTIC_MISMATCH naming fixture and field, "
                + "expected detail [" + expectedDetail + "], got: "
                + run.failures());
        check(run.compileDiagnosticComparisons() == 1,
            "the variant runs exactly one Compile Diagnostic comparison");
    }

    private static void secondErrorDiagnosticProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 4b: a second error diagnostic --");
        String corpusPath = "frontend/scratch/pin-probe-second.deal";
        String raw =
            "// @spec: Diagnostics\n"
            + "// @description: scratch two-error compile pin probe\n"
            + "// @expected: compile-error E3001\n"
            + "// @features: scratch\n"
            + "\n"
            + "export function main(): null {\n"
            + "  let x: int = \"nope\";\n"
            + "  let y: int = \"also\";\n"
            + "  return null;\n"
            + "}\n";
        // The pin describes the first diagnostic; the frontend emits two.
        String sidecar = compilePinSidecar("E3001", "7", "16",
            "Cannot assign string to int");
        ScratchCorpus scratch = writeScratchCorpus(corpusPath, raw, sidecar);
        DifferentialGate.GateRun run = runScratch(scratch.root(), Map.of(),
            Duration.ofSeconds(60));
        check(run.failures().stream()
                .anyMatch(f -> "compile-diagnostic".equals(f.kind())
                    && f.subject().equals(corpusPath)
                    && f.detail().contains(
                        "the fixture must emit exactly one error "
                            + "diagnostic, got 2")),
            "a fixture emitting a second error diagnostic fails "
                + "COMPILE_DIAGNOSTIC_MISMATCH naming the fixture, got: "
                + run.failures());
    }

    // =========================================================================
    // Experiment 5: divergent sidecar schema rejection (Verification 6)
    // =========================================================================

    private static void divergentSidecarSchemaProbe(int jobs)
            throws IOException {
        System.out.println("-- Experiment 5: divergent sidecar schema "
            + "rejection --");
        // (5a) the sanctioned split shape on a non-FFI fixture: the
        // compilation set carries no @extern-c import, so no divergence
        // is sanctioned.
        String corpusPath = "backend-runtime/scratch/divergent-a.deal";
        String divergent = "{\n"
            + "  \"version\": 1,\n"
            + "  \"backends\": {\n"
            + "    \"luajit\": { \"mode\": \"runtime-ok\", \"transcript\": "
            + "{ \"stdout\": \"\", \"stderr\": \"\" }, \"exitCode\": 0 },\n"
            + "    \"jvm\": { \"mode\": \"compile-reject\", \"diagnostic\": "
            + "{ \"code\": \"E6006\" } },\n"
            + "    \"js\": { \"mode\": \"compile-reject\", \"diagnostic\": "
            + "{ \"code\": \"E6006\" } }\n"
            + "  }\n"
            + "}\n";
        ScratchCorpus scratchA = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + ORDER_PROBE_SOURCE, divergent);
        DifferentialGate.GateRun runA = runScratch(scratchA.root(),
            lanes(scratchA.root(), null, null), Duration.ofSeconds(60));
        check(runA.classificationFailures().size() == 1
                && runA.classificationFailures().get(0).detail().contains(
                    "a divergent form is valid only for the C6 "
                        + "rejection: the fixture's compilation set "
                        + "contains no @extern-c import directive"),
            "a divergent sidecar on a non-FFI fixture is rejected by "
                + "schema validation naming the sidecar, got: "
                + runA.classificationFailures());
        check(runA.runtimeCasesDispatched() == 0,
            "the rejected sidecar dispatches no lane (no silent default)");

        // (5b) a divergent sidecar pinning a non-E6006 rejection code:
        // the sanctioned C6 divergence pins E6006 and no other code.
        String corpusPathB = "backend-runtime/scratch/divergent-b.deal";
        String divergentB = "{\n"
            + "  \"version\": 1,\n"
            + "  \"backends\": {\n"
            + "    \"luajit\": { \"mode\": \"runtime-ok\", \"transcript\": "
            + "{ \"stdout\": \"\", \"stderr\": \"\" }, \"exitCode\": 0 },\n"
            + "    \"jvm\": { \"mode\": \"compile-reject\", \"diagnostic\": "
            + "{ \"code\": \"E6005\" } },\n"
            + "    \"js\": { \"mode\": \"compile-reject\", \"diagnostic\": "
            + "{ \"code\": \"E6006\" } }\n"
            + "  }\n"
            + "}\n";
        ScratchCorpus scratchB = writeScratchCorpus(corpusPathB,
            HEADER_RUNTIME_OK + ORDER_PROBE_SOURCE, divergentB);
        DifferentialGate.GateRun runB = runScratch(scratchB.root(),
            lanes(scratchB.root(), null, null), Duration.ofSeconds(60));
        check(runB.classificationFailures().stream()
                .anyMatch(f -> f.detail().contains(
                    "the sanctioned C6 divergence pins the exact "
                        + "diagnostic code E6006 (FFI_UNSUPPORTED_BACKEND), "
                        + "got E6005")),
            "a divergent sidecar with a non-E6006 code is rejected by "
                + "schema validation, got: " + runB.classificationFailures());
        check(runB.runtimeCasesDispatched() == 0,
            "the rejected sidecar dispatches no lane (no silent default)");
    }

    // =========================================================================
    // Experiment 6: the harness-owned deadline (Verification 8)
    // =========================================================================

    private static final String HANG_PROBE_SOURCE =
        "export function test_hang(): null {\n"
        + "  while (true) {\n"
        + "  }\n"
        + "  return null;\n"
        + "}\n"
        + "\n"
        + "export function main(): null {\n"
        + "  return null;\n"
        + "}\n";

    private static void laneTimeoutProbe(int jobs) throws IOException {
        System.out.println("-- Experiment 6: a hung lane exceeds the "
            + "harness-owned deadline --");
        String corpusPath = "backend-runtime/scratch/hang-probe.deal";
        ScratchCorpus scratch = writeScratchCorpus(corpusPath,
            HEADER_RUNTIME_OK + HANG_PROBE_SOURCE, uniformSidecar(""));
        long start = System.nanoTime();
        DifferentialGate.GateRun run = runScratch(scratch.root(),
            lanes(scratch.root(), null, null), Duration.ofMillis(10_000));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        check(elapsedMillis < 60_000,
            "the deadline releases the case instead of blocking on the "
                + "hung subprocesses (elapsed " + elapsedMillis + " ms)");
        int timeouts = 0;
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            GateDispatcher.LaneOutcome outcome = outcomeOf(run, corpusPath,
                backend);
            if (outcome != null && !outcome.passed()
                    && outcome.mismatch().get().clazz()
                        == MismatchClass.LANE_TIMEOUT
                    && outcome.mismatch().get().subject().equals(backend)
                    && outcome.mismatch().get().detail().contains(
                        "exceeded the harness-owned deadline of 10000 ms")
                    && outcome.mismatch().get().detail().contains(
                        "the gate never retries a lane")) {
                timeouts++;
            }
        }
        check(timeouts == 3,
            "all three hung lanes fail LANE_TIMEOUT with process "
                + "termination and no retry, got " + timeouts + " of 3");
        check(run.failures().size() == 3
                && run.failures().stream().allMatch(
                    f -> "infrastructure".equals(f.kind())),
            "the three lane timeouts are infrastructure failures (never "
                + "satisfying the case), got: " + run.failures());
    }
}
