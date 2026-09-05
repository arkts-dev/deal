package deal.test.conformance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Dev-time gate-pass logger for the ISSUE-0360 JSON-slice absorption
 * (pin-before-delete evidence).
 *
 * <p>Runs the complete differential gate over the real on-disk corpus
 * with the three production lanes registered (real {@code luajit},
 * {@code javac}+{@code java}, and {@code node} subprocesses) — the same
 * lane registration the ISSUE-0357 corpus test uses — and prints one
 * outcome line per fixture per backend. The committed output
 * {@code test/conformance/json-absorption/gate-pass-log.txt} is the
 * per-case gate invocation log every absorbed pin references: an
 * absorbed destination is deleted only when its line in this log reads
 * {@code PASS} (or {@code frontend OK}/{@code compile pin OK} for
 * frontend destinations).
 *
 * <p>The class is compile-listed in {@code run_tests.sh} but
 * deliberately not added to the run phase: the full three-lane run is
 * the release gate's job after the flip; this logger reproduces it
 * dev-time for the absorption evidence (G5's temporary-coexistence
 * window).</p>
 */
public final class JsonAbsorptionGateLog {

    private JsonAbsorptionGateLog() {
        // Static utility; no instances.
    }

    /** Runs the full three-lane gate over the real corpus and prints
     * per-fixture, per-backend outcomes. */
    public static void main(String[] args) throws IOException {
        Path corpusRoot = args.length > 0
            ? Path.of(args[0])
            : Path.of("test", "conformance");
        int jobs = Integer.getInteger("deal.test.jobs",
            Runtime.getRuntime().availableProcessors());
        PrintStream out = new PrintStream(System.out, true,
            StandardCharsets.UTF_8);

        JvmLane jvm = new JvmLane(corpusRoot);
        Map<String, Lane> lanes = Map.of(
            "luajit", new LuaLane(corpusRoot),
            "jvm", jvm,
            "js", new JsLane(corpusRoot));
        Map<String, DifferentialGate.PreFlipSkipRegistry> registries =
            Map.of("jvm", jvm.preFlipSkipRegistry());

        long start = System.nanoTime();
        DifferentialGate.GateRun run = DifferentialGate.run(corpusRoot,
            lanes, jobs, Duration.ofSeconds(120), out, registries);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        out.println();
        out.println("=== JSON-absorption gate pass log (ISSUE-0360) ===");
        out.println("fixtures=" + run.fixtures().size()
            + " frontendCompiled=" + run.frontendCompiled()
            + " compilePins=" + run.compileDiagnosticComparisons()
            + " dispatched=" + run.runtimeCasesDispatched()
            + " deferred=" + run.runtimeCasesDeferred()
            + " knownFailuresTracked=" + run.knownFailuresTracked()
            + " skipped=" + run.skipped()
            + " skipRegistryTracked=" + run.skipRegistryTracked()
            + " elapsedMillis=" + elapsedMillis);
        for (DifferentialGate.GateFailure failure : run.failures()) {
            out.println("FAILURE " + failure.kind() + " "
                + failure.subject() + " " + failure.detail());
        }
        for (GateDispatcher.CaseVerdict verdict : run.verdicts()) {
            for (GateDispatcher.LaneOutcome outcome : verdict.outcomes()) {
                String detail = outcome.mismatch().map(m ->
                    m.clazz() + ": " + m.detail()).orElse("");
                out.println("VERDICT " + verdict.fixturePath() + " | "
                    + outcome.backend() + " | "
                    + (outcome.passed() ? "PASS" : "FAIL") + " | " + detail);
            }
        }
        out.flush();
        if (!run.ok()) {
            System.exit(1);
        }
    }
}
