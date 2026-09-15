package deal.test.conformance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GateDispatcher tests (ISSUE-0353 Verification): the G7 worker pool and
 * the harness-owned per-lane deadline, exercised with test doubles.
 *
 * <p><b>TEST DOUBLES:</b> every {@link Lane} implementation in this file
 * is a dispatch/verdict unit-test double (the production lanes land in
 * T7-T9) — the scripted lane returns canned outcomes, the recording lane
 * captures its executing worker thread, and the hung-process lane spawns
 * a real subprocess it kills on interrupt so the deadline semantics are
 * exercised against a real process. The doubles are confined to this
 * unit test and flagged as such; all comparator/classification
 * verification in the gate core is real.</p>
 */
public class GateDispatcherTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== GateDispatcher Tests (ISSUE-0353) ===\n");

        passingVerdicts();
        differentialFailures();
        missingLaneIsHarnessDefect();
        hungLaneKilledByDeadlineNoRetry();
        workerBoundByParallelism();
        laneNameMismatchIsHarnessDefect();

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
    // Test doubles (flagged — dispatch/verdict unit tests only)
    // =========================================================================

    private static final byte[] OUT = "hello\n".getBytes(StandardCharsets.UTF_8);

    /**
     * TEST DOUBLE — a scripted lane returning one canned execution outcome
     * per case, keyed by fixture path (parallel case workers must not race
     * an invocation index); cases not in the script return the passing
     * outcome.
     */
    private static final class ScriptedLane implements Lane {
        private final String name;
        private final Map<String, LaneExecution> script;
        private final AtomicInteger invocations = new AtomicInteger();

        ScriptedLane(String name, Map<String, LaneExecution> script) {
            this.name = name;
            this.script = Map.copyOf(script);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public LaneExecution execute(LaneCase laneCase) {
            invocations.incrementAndGet();
            return script.getOrDefault(laneCase.fixturePath(),
                new LaneExecution.Executed(OUT, new byte[0], 0));
        }

        int invocations() {
            return invocations.get();
        }
    }

    /**
     * TEST DOUBLE — a recording lane capturing the thread names of its
     * executing workers (for the worker-bound assertion) and returning
     * a passing outcome.
     */
    private static final class RecordingLane implements Lane {
        private final String name;
        private final Set<String> workerThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger invocations = new AtomicInteger();

        RecordingLane(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public LaneExecution execute(LaneCase laneCase) {
            workerThreads.add(Thread.currentThread().getName());
            invocations.incrementAndGet();
            return new LaneExecution.Executed(OUT, new byte[0], 0);
        }

        int invocations() {
            return invocations.get();
        }

        Set<String> workerThreads() {
            return workerThreads;
        }
    }

    /**
     * TEST DOUBLE — a lane that spawns a real sleeping subprocess and
     * blocks on it, killing the subprocess on interrupt (the Lane
     * contract's deadline-termination requirement). The double records
     * every invocation so the no-retry rule is observable.
     */
    private static final class HungProcessLane implements Lane {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<Process> spawned = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "luajit";
        }

        @Override
        public LaneExecution execute(LaneCase laneCase) throws Exception {
            invocations.incrementAndGet();
            Process process = new ProcessBuilder("sh", "-c", "sleep 60")
                .start();
            spawned.add(process);
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                // The Lane contract: terminate the subprocess on interrupt
                // (the harness-owned deadline enforcement depends on it).
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw e;
            }
            return new LaneExecution.Executed(new byte[0], new byte[0], 0);
        }

        int invocations() {
            return invocations.get();
        }

        List<Process> spawned() {
            return spawned;
        }
    }

    private static SidecarExpectations.RuntimeExpectation okExpectation() {
        return new SidecarExpectations.RuntimeExpectation.Executed(
            "runtime-ok", OUT, new byte[0], 0, null);
    }

    private static LaneCase laneCase(String fixturePath, String backend) {
        return new LaneCase(fixturePath, backend,
            Path.of("test", "conformance", fixturePath),
            List.of(new SidecarSchemaValidator.CompilationModule(fixturePath,
                "export function main(): null { return null; }")),
            okExpectation());
    }

    private static GateDispatcher.CaseInput caseOf(String fixturePath) {
        List<LaneCase> laneCases = new ArrayList<>();
        for (String backend : SidecarSchemaValidator.BACKEND_NAMES) {
            laneCases.add(laneCase(fixturePath, backend));
        }
        return new GateDispatcher.CaseInput(fixturePath, laneCases);
    }

    private static Map<String, Lane> lanes(Lane... implementations) {
        Map<String, Lane> map = new LinkedHashMap<>();
        for (Lane lane : implementations) {
            map.put(lane.name(), lane);
        }
        return map;
    }

    // =========================================================================
    // Cases
    // =========================================================================

    private static void passingVerdicts() {
        ScriptedLane lua = new ScriptedLane("luajit", Map.of());
        ScriptedLane jvm = new ScriptedLane("jvm", Map.of());
        ScriptedLane js = new ScriptedLane("js", Map.of());

        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(caseOf("backend-runtime/a.deal"),
                caseOf("backend-runtime/b.deal")),
            lanes(lua, jvm, js), 2, Duration.ofSeconds(5));

        check(verdicts.size() == 2, "two cases yield two verdicts");
        check(verdicts.get(0).passed() && verdicts.get(1).passed(),
            "all-lanes-passing cases pass");
        check(verdicts.get(0).fixturePath().equals("backend-runtime/a.deal")
                && verdicts.get(1).fixturePath()
                    .equals("backend-runtime/b.deal"),
            "verdicts keep case-input order");
        check(verdicts.get(0).outcomes().size() == 3,
            "one outcome per backend lane");
        List<String> backendOrder = verdicts.get(0).outcomes().stream()
            .map(GateDispatcher.LaneOutcome::backend).toList();
        check(backendOrder.equals(SidecarSchemaValidator.BACKEND_NAMES),
            "lane outcomes keep the sidecar backend order, got " + backendOrder);
        check(lua.invocations() == 2 && jvm.invocations() == 2
                && js.invocations() == 2,
            "every lane of every case executes exactly once");
    }

    private static void differentialFailures() {
        // The JS lane diverges on the first case only: a transcript
        // mismatch names the backend lane, the case fails, and the other
        // case still passes.
        ScriptedLane lua = new ScriptedLane("luajit", Map.of());
        ScriptedLane jvm = new ScriptedLane("jvm", Map.of());
        ScriptedLane js = new ScriptedLane("js", Map.of(
            "backend-runtime/a.deal",
            new LaneExecution.Executed(
                "hallo\n".getBytes(StandardCharsets.UTF_8), new byte[0], 0)));

        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(caseOf("backend-runtime/a.deal"),
                caseOf("backend-runtime/b.deal")),
            lanes(lua, jvm, js), 2, Duration.ofSeconds(5));

        check(!verdicts.get(0).passed(), "the divergent case fails");
        check(verdicts.get(1).passed(), "the matching case passes");
        Optional<GateMismatch> mismatch = verdicts.get(0).outcomes().stream()
            .filter(o -> o.backend().equals("js"))
            .findFirst().get().mismatch();
        check(mismatch.isPresent()
                && mismatch.get().clazz() == MismatchClass.TRANSCRIPT_MISMATCH
                && mismatch.get().subject().equals("js")
                && mismatch.get().detail().contains("stdout differs at byte 1"),
            "the divergence names the backend lane and the first differing "
                + "byte, got: " + mismatch);
    }

    private static void missingLaneIsHarnessDefect() {
        ScriptedLane lua = new ScriptedLane("luajit", Map.of());
        ScriptedLane jvm = new ScriptedLane("jvm", Map.of());

        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(caseOf("backend-runtime/a.deal")),
            lanes(lua, jvm), 2, Duration.ofSeconds(5));

        check(!verdicts.get(0).passed(),
            "a case with an unregistered lane never passes");
        Optional<GateMismatch> mismatch = verdicts.get(0).outcomes().stream()
            .filter(o -> o.backend().equals("js"))
            .findFirst().get().mismatch();
        check(mismatch.isPresent()
                && mismatch.get().clazz() == MismatchClass.HARNESS_DEFECT
                && mismatch.get().infrastructure(),
            "the unregistered lane is HARNESS_DEFECT labeled infrastructure "
                + "(no silent lane omission), got: " + mismatch);
        check(mismatch.get().detail().contains("no lane implementation"),
            "the detail names the omission, got: "
                + mismatch.get().detail());
    }

    private static void hungLaneKilledByDeadlineNoRetry() {
        HungProcessLane lua = new HungProcessLane();
        ScriptedLane jvm = new ScriptedLane("jvm", Map.of());
        ScriptedLane js = new ScriptedLane("js", Map.of());

        long start = System.nanoTime();
        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(caseOf("backend-runtime/a.deal")),
            lanes(lua, jvm, js), 2, Duration.ofMillis(800));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        Optional<GateMismatch> mismatch = verdicts.get(0).outcomes().stream()
            .filter(o -> o.backend().equals("luajit"))
            .findFirst().get().mismatch();
        check(mismatch.isPresent()
                && mismatch.get().clazz() == MismatchClass.LANE_TIMEOUT
                && mismatch.get().infrastructure(),
            "the hung lane is LANE_TIMEOUT labeled infrastructure, got: "
                + mismatch);
        check(mismatch.isPresent() && mismatch.get().detail()
                .contains("deadline of 800 ms"),
            "the detail names the harness-owned deadline, got: "
                + mismatch.map(GateMismatch::detail).orElse("<none>"));
        check(elapsedMillis < 30_000,
            "the deadline releases the worker instead of blocking on the "
                + "hung process (elapsed " + elapsedMillis + " ms)");
        check(lua.invocations() == 1,
            "the timed-out lane is never retried (invocations: "
                + lua.invocations() + ")");
        check(lua.spawned().size() == 1
                && !lua.spawned().get(0).isAlive(),
            "the synthetic hung lane process is killed by the deadline");
        check(!verdicts.get(0).passed(),
            "the infrastructure outcome never satisfies the case");
    }

    private static void workerBoundByParallelism() {
        RecordingLane lua = new RecordingLane("luajit");
        RecordingLane jvm = new RecordingLane("jvm");
        RecordingLane js = new RecordingLane("js");
        List<GateDispatcher.CaseInput> cases = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            cases.add(caseOf("backend-runtime/case-" + i + ".deal"));
        }
        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(cases,
            lanes(lua, jvm, js), 2, Duration.ofSeconds(5));

        Set<String> distinctThreads = new java.util.HashSet<>();
        distinctThreads.addAll(lua.workerThreads());
        distinctThreads.addAll(jvm.workerThreads());
        distinctThreads.addAll(js.workerThreads());
        check(verdicts.size() == 8 && verdicts.stream().allMatch(
                GateDispatcher.CaseVerdict::passed),
            "eight cases complete under the two-worker pool");
        check(distinctThreads.size() <= 2,
            "the worker pool is bounded by the parallelism parameter "
                + "(distinct gate workers: " + distinctThreads.size() + ")");
        check(lua.invocations() + jvm.invocations() + js.invocations() == 24,
            "every lane of every case executes exactly once under the pool");
    }

    private static void laneNameMismatchIsHarnessDefect() {
        // A lane registered under a backend key it does not report: it is
        // registered under "luajit" while reporting the name "jvm".
        Lane mismatched = new Lane() {
            @Override
            public String name() {
                return "jvm";
            }

            @Override
            public LaneExecution execute(LaneCase laneCase) {
                return new LaneExecution.Executed(OUT, new byte[0], 0);
            }
        };
        List<GateDispatcher.CaseVerdict> verdicts = GateDispatcher.run(
            List.of(caseOf("backend-runtime/a.deal")),
            Map.of("luajit", mismatched), 2, Duration.ofSeconds(5));
        Optional<GateMismatch> mismatch = verdicts.get(0).outcomes().stream()
            .filter(o -> o.backend().equals("luajit"))
            .findFirst().get().mismatch();
        check(mismatch.isPresent()
                && mismatch.get().clazz() == MismatchClass.HARNESS_DEFECT,
            "a lane reporting a name other than its registration key is "
                + "HARNESS_DEFECT, got: " + mismatch);
    }
}
