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
        long frontend = run.fixtures().stream()
            .filter(f -> f.phase().equals("frontend")).count();
        long backendRuntime = run.fixtures().stream()
            .filter(f -> f.phase().equals("backend-runtime")).count();
        long classified = 0;
        for (CorpusDiscovery.Kind kind : CorpusDiscovery.Kind.values()) {
            classified += countKind(run, kind);
        }
        check(frontend + backendRuntime == run.fixtures().size(),
            "frontend and backend-runtime phases account for every fixture");
        check(classified == run.fixtures().size(),
            "fixture classifications account for every discovered fixture");
        check(run.classificationFailures().isEmpty(),
            "the real corpus classifies with zero classification failures: "
                + run.classificationFailures());
        check(countKind(run, CorpusDiscovery.Kind.KNOWN_FAIL) == 0,
            "the corpus carries zero known-fail fixtures");
    }

    private static long countKind(DifferentialGate.GateRun run,
            CorpusDiscovery.Kind kind) {
        return run.fixtures().stream()
            .filter(f -> f.classification() != null
                && f.classification().kind() == kind)
            .count();
    }

    private static void sidecarValidation(DifferentialGate.GateRun run) {
        check(run.loadedFixtures().size() == run.fixtures().size(),
            "every discovered fixture carries a load result");
        long runtimeFixtures = run.fixtures().stream()
            .filter(CorpusDiscovery.Fixture::runtimeClassified).count();
        long runtimeLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().runtime().isPresent()).count();
        check(runtimeLoaded == runtimeFixtures,
            "every runtime-classified fixture's sidecar loads and validates "
                + "under T1 with its compilation set");
        long runtimeOkFixtures = countKind(run, CorpusDiscovery.Kind.RUNTIME_OK);
        long runtimeOkLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().runtime().isPresent()
                && "runtime-ok".equals(f.fixture().classification()
                    .runtimeSidecarMode()))
            .count();
        check(runtimeOkLoaded == runtimeOkFixtures,
            "every runtime-ok fixture carries a loaded sidecar");
        long pinLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().compilePin().isPresent()).count();
        check(pinLoaded == run.compileDiagnosticComparisons(),
            "every loaded compile pin is compared against the frontend");
    }

    private static void compileDiagnosticPins(DifferentialGate.GateRun run) {
        check(run.compileDiagnosticComparisons() == run.loadedFixtures().stream()
                .filter(f -> f.load().compilePin().isPresent()).count(),
            "every real Diagnostics-bullet fixture is compared against "
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
        check(run.knownFailuresTracked() == 0,
            "the tracked known-fail population is empty, got "
                + run.knownFailuresTracked());
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
        CorpusDiscovery.Fixture promotedBytes = run.fixtures().stream()
            .filter(f -> f.corpusPath().equals(
                "backend-runtime/bytes/bytes-boundary-order.deal"))
            .findFirst().orElseThrow();
        check(promotedBytes.classification() != null
                && promotedBytes.classification().kind()
                    == CorpusDiscovery.Kind.RUNTIME_OK,
            "the ISSUE-0502 gap bytes-boundary-order fixture passes "
                + "every lane after ISSUE-0158 lifted the E3019 "
                + "bytes-equality gate and classifies as a real "
                + "runtime-ok fixture, got "
                + promotedBytes.classification());
    }

    private static void dispatchDeferral(DifferentialGate.GateRun run) {
        long runtimeFixtures = run.fixtures().stream()
            .filter(CorpusDiscovery.Fixture::runtimeClassified).count();
        check(run.runtimeCasesDeferred() == runtimeFixtures,
            "runtime dispatch defers every discovered runtime case in this "
                + "child, got " + run.runtimeCasesDeferred() + " of "
                + runtimeFixtures);
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
        long runtimeErrorLoaded = run.loadedFixtures().stream()
            .filter(f -> f.load().runtime().isPresent()
                && "runtime-error".equals(f.fixture().classification()
                    .runtimeSidecarMode()))
            .count();
        check(checked == runtimeErrorLoaded,
            "the canonical-consistency check covers every loaded "
                + "runtime-error sidecar, got " + checked + " of "
                + runtimeErrorLoaded);
    }
}
