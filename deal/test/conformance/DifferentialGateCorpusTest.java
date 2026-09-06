package deal.test.conformance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Differential gate corpus test (ISSUE-0353 Verification, anti-hollow):
 * the gate core over the real on-disk corpus and the real frontend —
 * discovery, classification, sidecar validation (T1 + compilation sets),
 * the Compile Diagnostic comparison on the real Diagnostics fixtures, the
 * tracked known-fail population, and the canonical-serialization
 * consistency of every authored runtime-error snapshot against the
 * gate's own canonical serializer and framing parser.
 *
 * <p>Read-only over {@code test/conformance/}; lane dispatch is deferred
 * in this child (no lane implementations land before T7-T9), so no lane
 * executes here — the runtime corpus stays with the legacy runners
 * during the G5 temporary-coexistence window.</p>
 */
public class DifferentialGateCorpusTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final Path CORPUS_ROOT = Path.of("test", "conformance");

    /** The pinned corpus population (the T2/T3 sidecar population pins).
     * ISSUE-0378 adds the restored known-fail fixture
     * {@code backend-runtime/arithmetic/int-add-overflow.deal} (canonical
     * known-fail header, byte-exact restoration): +1 total fixture, +1
     * backend-runtime fixture, +1 known-fail, +1 runtime-error sidecar
     * (its underlying runtime-error E8004 mode carries the mandatory
     * three-backend sidecar like every other runtime-classified
     * fixture). ISSUE-0380 (the disposition-application unit) flips
     * {@code backend-runtime/stdlib-edge/time-now-millis-positive.deal}
     * to {@code runtime-error E8004} (runtime-ok 219 -> 218, sidecar
     * re-authored) and promotes the restored int-add-overflow fixture
     * (runtime-error 81 -> 83, its sidecar unchanged; known-fail
     * 2 -> 1: only the FFI manifest frontend pin stays tracked; the
     * runtime-error-sidecar population 82 -> 83). ISSUE-0501 (the
     * compile-classified gap-fixture landing) adds the 63 gap fixtures
     * under {@code frontend/} (type-system 2, lexer 21, modules 9,
     * diagnostics 3, bytes 4, types 16, tables 2 — 57 classified
     * cases: +20 compile-ok, +37 compile-error; +6 companions) and
     * the two Diagnostics-bullet compile sidecars: total 465 -> 528,
     * frontend 130 -> 193, compile-ok 38 -> 58, compile-error
     * 91 -> 128, companions 34 -> 40, compile pins 2 -> 4; the
     * runtime populations (218/83) and the known-fail population
     * (the one FFI manifest frontend pin) are unchanged.
     * ISSUE-0500 (v12-gap-suite-integration E1/E2 co-landing) adds the
     * preserved gap resolution subtree
     * {@code frontend/modules/resolution/} (six compile-classified root
     * fixtures + {@code nested/relative-parent} + nine discovery
     * companions) and the transformed direct declaration fixture
     * {@code frontend/modules/declaration-expression-statement-rejected.d.deal}
     * (compile-error E7001): total 528 -> 545, frontend 193 -> 210,
     * compile-ok 58 -> 64, compile-error 128 -> 130, companions
     * 40 -> 49; the runtime populations (218/83), the known-fail
     * population, and the compile pins (4) are unchanged.
     * This tree (ISSUE-0477, the ISSUE-0402 acceptance remediation)
     * promotes the last known-fail — the FFI manifest pin
     * {@code frontend/modules/ffi-manifest-missing-native-library-rejected.deal}
     * — to a real compile-error fixture (compile-error 130 -> 131,
     * known-fail 1 -> 0) in the same change as its production E2010
     * emission site (deal/checker/NameResolver.java at the import span),
     * so the corpus carries zero known-fail fixtures. The gate's own
     * real-frontend corpus phase observes the promotion too:
     * {@code CorpusFrontendResolver} enforces the same v1.2 C FFI
     * manifest policy (an import of an effective-{@code @extern-c}
     * {@code .d.deal} corpus module without a manifest entry raises the
     * checker's E2010 at the import span), so the promoted fixture
     * records {@code frontend OK (found E2010)} through the real
     * pipeline, never a manufactured pin. */
    private static final int TOTAL_FIXTURES = 545;
    private static final int FRONTEND_FIXTURES = 210;
    private static final int BACKEND_RUNTIME_FIXTURES = 335;
    private static final int COMPILE_OK = 64;
    private static final int COMPILE_ERROR = 131;
    private static final int RUNTIME_OK = 218;
    private static final int RUNTIME_ERROR = 83;
    private static final int RUNTIME_ERROR_SIDECARS = 83;
    private static final int COMPANIONS = 49;
    private static final int KNOWN_FAIL = 0;
    private static final int COMPILE_PINS = 4;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Differential Gate Corpus Tests (ISSUE-0353) ===\n");

        PrintStream capture = new PrintStream(new ByteArrayOutputStream(),
            true, StandardCharsets.UTF_8);
        DifferentialGate.GateRun run = DifferentialGate.run(CORPUS_ROOT,
            Map.of(), 4, Duration.ofSeconds(5), capture);

        discoveryAndClassification(run);
        sidecarValidation(run);
        compileDiagnosticPins(run);
        knownFailTracking(run);
        dispatchDeferral(run);
        companionThrowFixtures(run);
        canonicalSnapshotConsistency(run);

        System.out.println("\nPassed: " + passed + ", Failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String message) {
        if (condition) { passed++; }
        else { failed++; System.err.println("FAIL: " + message); }
    }

    // =========================================================================
    // Cases
    // =========================================================================

    private static void discoveryAndClassification(DifferentialGate.GateRun run) {
        check(run.fixtures().size() == TOTAL_FIXTURES,
            "the real corpus discovers exactly " + TOTAL_FIXTURES
                + " fixtures, got " + run.fixtures().size());
        check(run.fixtures().stream()
                .filter(f -> f.phase().equals("frontend")).count()
                == FRONTEND_FIXTURES,
            "frontend phase carries exactly " + FRONTEND_FIXTURES
                + " fixtures");
        check(run.fixtures().stream()
                .filter(f -> f.phase().equals("backend-runtime")).count()
                == BACKEND_RUNTIME_FIXTURES,
            "backend-runtime phase carries exactly "
                + BACKEND_RUNTIME_FIXTURES + " fixtures");
        check(run.classificationFailures().isEmpty(),
            "the real corpus classifies with zero classification failures: "
                + run.classificationFailures());
        check(countKind(run, CorpusDiscovery.Kind.COMPILE_OK) == COMPILE_OK,
            "compile-ok fixtures: " + COMPILE_OK);
        check(countKind(run, CorpusDiscovery.Kind.COMPILE_ERROR)
                == COMPILE_ERROR,
            "compile-error fixtures: " + COMPILE_ERROR);
        check(countKind(run, CorpusDiscovery.Kind.RUNTIME_OK) == RUNTIME_OK,
            "runtime-ok fixtures: " + RUNTIME_OK);
        check(countKind(run, CorpusDiscovery.Kind.RUNTIME_ERROR)
                == RUNTIME_ERROR,
            "runtime-error fixtures: " + RUNTIME_ERROR);
        check(countKind(run, CorpusDiscovery.Kind.COMPANION) == COMPANIONS,
            "companion fixtures: " + COMPANIONS);
        check(countKind(run, CorpusDiscovery.Kind.KNOWN_FAIL) == KNOWN_FAIL,
            "known-fail fixtures: " + KNOWN_FAIL);
    }

    private static long countKind(DifferentialGate.GateRun run,
            CorpusDiscovery.Kind kind) {
        return run.fixtures().stream()
            .filter(f -> f.classification() != null
                && f.classification().kind() == kind)
            .count();
    }

    private static void sidecarValidation(DifferentialGate.GateRun run) {
        check(run.loadedFixtures().size() == TOTAL_FIXTURES,
            "every discovered fixture carries a load result");
        long runtimeLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().runtime().isPresent()).count();
        check(runtimeLoaded == RUNTIME_OK + RUNTIME_ERROR_SIDECARS,
            "every runtime-classified fixture's sidecar loads and validates "
                + "under T1 with its compilation set: " + (RUNTIME_OK
                + RUNTIME_ERROR_SIDECARS) + " loaded, got " + runtimeLoaded);
        long runtimeOkLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().runtime().isPresent()
                && "runtime-ok".equals(f.fixture().classification()
                    .runtimeSidecarMode()))
            .count();
        check(runtimeOkLoaded == RUNTIME_OK,
            "runtime-ok sidecars: " + RUNTIME_OK + ", got " + runtimeOkLoaded);
        long pinLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().compilePin().isPresent()).count();
        check(pinLoaded == COMPILE_PINS,
            "compile pin sidecars: " + COMPILE_PINS + ", got " + pinLoaded);
    }

    private static void compileDiagnosticPins(DifferentialGate.GateRun run) {
        check(run.compileDiagnosticComparisons() == COMPILE_PINS,
            "the four real Diagnostics-bullet fixtures are compared against "
                + "the real frontend");
        check(run.failures().stream()
                .noneMatch(f -> f.kind().equals("compile-diagnostic")),
            "the authored pins match the real frontend field-exact: "
                + run.failures().stream()
                    .filter(f -> f.kind().equals("compile-diagnostic"))
                    .map(DifferentialGate.GateFailure::message)
                    .toList());
    }

    private static void knownFailTracking(DifferentialGate.GateRun run) {
        check(run.knownFailuresTracked() == KNOWN_FAIL,
            "the tracked known-fail population is exactly " + KNOWN_FAIL
                + " non-fatal, got " + run.knownFailuresTracked());
        check(run.skipped() == 0,
            "the pre-flip Skipped counter stays zero");
        List<String> knownFailPaths = run.fixtures().stream()
            .filter(f -> f.classification() != null
                && f.classification().kind() == CorpusDiscovery.Kind.KNOWN_FAIL)
            .map(CorpusDiscovery.Fixture::corpusPath)
            .sorted()
            .toList();
        check(knownFailPaths.equals(List.of()),
            "the tracked known-fail population is empty (this tree "
                + "promoted the last known-fail — the FFI manifest pin — "
                + "to compile-error E2010 in the same change as its "
                + "production emission site), got " + knownFailPaths);
        CorpusDiscovery.Fixture promoted = run.fixtures().stream()
            .filter(f -> f.corpusPath().equals(
                "frontend/modules/ffi-manifest-missing-native-library-rejected.deal"))
            .findFirst().orElseThrow();
        check(promoted.classification() != null
                && promoted.classification().kind()
                    == CorpusDiscovery.Kind.COMPILE_ERROR,
            "the former FFI manifest known-fail pin now classifies as a "
                + "real compile-error fixture, got "
                + promoted.classification());
        CorpusDiscovery.Fixture promotedOverflow = run.fixtures().stream()
            .filter(f -> f.corpusPath().equals(
                "backend-runtime/arithmetic/int-add-overflow.deal"))
            .findFirst().orElseThrow();
        check(promotedOverflow.classification() != null
                && promotedOverflow.classification().kind()
                    == CorpusDiscovery.Kind.RUNTIME_ERROR,
            "the restored int-add-overflow known-fail fixture now "
                + "classifies as a real runtime-error fixture, got "
                + promotedOverflow.classification());
    }

    private static void dispatchDeferral(DifferentialGate.GateRun run) {
        check(run.runtimeCasesDeferred() == RUNTIME_OK + RUNTIME_ERROR_SIDECARS,
            "the runtime dispatch defers exactly the " + (RUNTIME_OK
                + RUNTIME_ERROR_SIDECARS) + " runtime cases in this child (no lane "
                + "implementations land before T7-T9), got "
                + run.runtimeCasesDeferred());
        check(run.runtimeCasesDispatched() == 0,
            "zero cases dispatched in this child");
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            int[] counts = run.perBackend().get(backend);
            check(counts[0] == 0 && counts[1] == 0,
                "per-backend counters start at zero for " + backend);
        }
        check(run.ok(),
            "the gate core over the real corpus passes (zero classification "
                + "failures, zero mismatches), got failures: " + run.failures());
    }

    private static void companionThrowFixtures(DifferentialGate.GateRun run) {
        // The two companion-throw fixtures' sidecars name their throwing
        // companions — the gate derives each compilation set by
        // module-import resolution and T1 validates the sourceFile graph.
        String[] fixtures = {
            "backend-runtime/module-failures/imported-function-explicit-error.deal",
            "backend-runtime/source-location/module-error-source.deal"
        };
        String[] companions = {
            "backend-runtime/module-failures/explicit_fail_lib.deal",
            "backend-runtime/source-location/source_module_lib.deal"
        };
        for (int i = 0; i < fixtures.length; i++) {
            String fixturePath = fixtures[i];
            String companionPath = companions[i];
            CorpusDiscovery.Fixture fixture = run.fixtures().stream()
                .filter(f -> f.corpusPath().equals(fixturePath))
                .findFirst().orElseThrow();
            check(fixture.classification() != null
                    && fixture.runtimeClassified(),
                fixtures[i] + " is a runtime-classified fixture");
            List<SidecarSchemaValidator.CompilationModule> set =
                CorpusDiscovery.compilationSet(fixture,
                    runIndex(run), CORPUS_ROOT);
            check(set.stream().anyMatch(m ->
                    m.corpusPath().equals(companionPath)),
                fixturePath + "'s compilation set contains its throwing "
                    + "companion " + companionPath + ", got: "
                    + set.stream().map(SidecarSchemaValidator.CompilationModule
                        ::corpusPath).toList());
        }
        // Their sidecars validated clean through the gate (loaded runtime).
        check(run.loadedFixtures().stream()
                .filter(f -> f.fixture().corpusPath().equals(fixtures[0])
                    || f.fixture().corpusPath().equals(fixtures[1]))
                .allMatch(f -> f.load().runtime().isPresent()
                    && !f.load().failed()),
            "the companion-throw fixtures' sidecars validate clean (they "
                + "name their throwing companions in the compilation set)");
    }

    private static Map<String, CorpusDiscovery.Fixture> runIndex(
            DifferentialGate.GateRun run) {
        return new java.util.HashMap<>() {{
            for (CorpusDiscovery.Fixture fixture : run.fixtures()) {
                put(fixture.corpusPath(), fixture);
            }
        }};
    }

    private static void canonicalSnapshotConsistency(
            DifferentialGate.GateRun run) throws IOException {
        int checked = 0;
        for (DifferentialGate.LoadedFixture loaded : run.loadedFixtures()) {
            if (loaded.load().runtime().isEmpty()
                    || !"runtime-error".equals(loaded.fixture()
                        .classification().runtimeSidecarMode())) {
                continue;
            }
            checked++;
            String corpusPath = loaded.fixture().corpusPath();
            String sidecarText = Files.readString(
                loaded.fixture().sidecarPath(CORPUS_ROOT));
            SidecarExpectations.StructuredExpectationSidecar sidecar =
                SidecarExpectations.StructuredExpectationSidecar.parse(
                    sidecarText);
            SidecarExpectations.RuntimeExpectation.Executed expected =
                (SidecarExpectations.RuntimeExpectation.Executed)
                    sidecar.expectationFor("luajit");

            // The authored transcript's snapshot byte-equals the canonical
            // serialization recomputed from the authored error object.
            String canonical = ErrorSnapshot.canonicalJson(expected.error());
            String canonicalFraming = ErrorSnapshot.CODE_LINE_PREFIX
                + expected.error().code() + "\n"
                + ErrorSnapshot.SNAPSHOT_LINE_PREFIX + canonical + "\n";
            check(new String(expected.stdout(), StandardCharsets.UTF_8)
                    .equals(canonicalFraming),
                corpusPath + ": the authored snapshot transcript byte-equals "
                    + "the gate's canonical serialization");

            // The gate's framing parser accepts every authored transcript
            // and extracts the exact authored field set.
            Optional<ErrorSnapshot.Framed> framed =
                ErrorSnapshot.parseFraming(expected.stdout());
            check(framed.isPresent(),
                corpusPath + ": the framing parser accepts the authored "
                    + "transcript");
            if (framed.isPresent()) {
                SidecarExpectations.ErrorExpectation fields =
                    framed.get().fields();
                check(fields.equals(expected.error()),
                    corpusPath + ": the parsed snapshot equals the authored "
                        + "Error Expectation field-exactly");
            }
        }
        check(checked == RUNTIME_ERROR_SIDECARS,
            "the canonical-consistency check covers all " + RUNTIME_ERROR_SIDECARS
                + " runtime-error sidecars, got " + checked);
    }
}
