package deal.test.conformance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The differential gate's lane dispatcher (ISSUE-0353; design
 * {@code v12-zero-skip-conformance-gate} G7): runs every case's lane
 * executions under a worker pool bounded by available processors, with a
 * harness-owned deadline per lane subprocess execution.
 *
 * <p>Dispatch semantics (G4/G6/G7):</p>
 * <ul>
 *   <li>Cases run in parallel workers; the worker count is the caller's
 *       {@code parallelism} (the gate passes
 *       {@code Runtime.getRuntime().availableProcessors()}, the
 *       worker-pool bound of the BackendConformanceTest pattern).</li>
 *   <li>Lanes of one case execute sequentially in the case's worker, in
 *       {@link LaneCase} order, so per-case verdict construction is
 *       deterministic and no worker ever blocks on a sibling lane
 *       queued behind itself.</li>
 *   <li>Each lane execution carries a harness-owned deadline: a timer
 *       cancels the execution when it is exceeded, the lane's execution
 *       thread is interrupted (the lane contract requires the lane to
 *       terminate its subprocess on interrupt), and the outcome is
 *       {@link MismatchClass#LANE_TIMEOUT} with process termination —
 *       there is no automatic retry.</li>
 *   <li>No retry, no expectation auto-update, no per-backend weakening:
 *       every lane of every case executes exactly once and the
 *       comparator's verdict is exact.</li>
 *   <li>No silent lane omission: a backend whose lane implementation is
 *       not registered is {@link MismatchClass#HARNESS_DEFECT}, an
 *       infrastructure outcome that never satisfies the case.</li>
 * </ul>
 */
public final class GateDispatcher {

    private GateDispatcher() {
        // Static utility; no instances.
    }

    /** One case's dispatch unit list (one per backend of the sidecar). */
    public record CaseInput(String fixturePath, List<LaneCase> laneCases) {

        public CaseInput {
            Objects.requireNonNull(fixturePath, "fixturePath must not be null");
            Objects.requireNonNull(laneCases, "laneCases must not be null");
            laneCases = List.copyOf(laneCases);
        }
    }

    /** One backend's dispatch outcome. */
    public record LaneOutcome(String backend, Optional<GateMismatch> mismatch) {

        public LaneOutcome {
            Objects.requireNonNull(backend, "backend must not be null");
            Objects.requireNonNull(mismatch, "mismatch must not be null");
        }

        /** True when the lane matched its expectation exactly. */
        public boolean passed() {
            return mismatch.isEmpty();
        }
    }

    /** One case's verdict: every lane outcome in lane-case order. */
    public record CaseVerdict(String fixturePath, List<LaneOutcome> outcomes) {

        public CaseVerdict {
            Objects.requireNonNull(fixturePath, "fixturePath must not be null");
            Objects.requireNonNull(outcomes, "outcomes must not be null");
            outcomes = List.copyOf(outcomes);
        }

        /** True when every lane matched its expectation exactly (G6). */
        public boolean passed() {
            return outcomes.stream().allMatch(LaneOutcome::passed);
        }
    }

    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    /**
     * Dispatches every case's lane executions under a worker pool of
     * {@code parallelism} workers with a per-lane deadline of
     * {@code perLaneDeadline}. Verdicts are returned in case-input order;
     * the verdict of a case passes only when every lane matched its
     * expectation exactly.
     *
     * @param cases           the cases to dispatch, in order
     * @param lanes           the registered lane implementations by backend name
     * @param parallelism     the worker count (bounded by available
     *                        processors at the call site)
     * @param perLaneDeadline the harness-owned deadline per lane execution
     */
    public static List<CaseVerdict> run(List<CaseInput> cases,
            Map<String, Lane> lanes, int parallelism, Duration perLaneDeadline) {
        Objects.requireNonNull(cases, "cases must not be null");
        Objects.requireNonNull(lanes, "lanes must not be null");
        Objects.requireNonNull(perLaneDeadline, "perLaneDeadline must not be null");
        int workers = Math.max(1, parallelism);

        ExecutorService pool = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable,
                "gate-worker-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gate-deadline-timer");
                thread.setDaemon(true);
                return thread;
            });
        try {
            List<Future<CaseVerdict>> futures = new ArrayList<>();
            for (CaseInput caseInput : cases) {
                futures.add(pool.submit(() ->
                    runCase(caseInput, lanes, timer, perLaneDeadline)));
            }
            List<CaseVerdict> verdicts = new ArrayList<>();
            for (Future<CaseVerdict> future : futures) {
                try {
                    verdicts.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                        "the gate dispatcher was interrupted while collecting "
                            + "case verdicts", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(
                        "the gate dispatcher's case worker failed", e.getCause());
                }
            }
            return verdicts;
        } finally {
            pool.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static CaseVerdict runCase(CaseInput caseInput,
            Map<String, Lane> lanes, ScheduledExecutorService timer,
            Duration perLaneDeadline) {
        List<LaneOutcome> outcomes = new ArrayList<>();
        for (LaneCase laneCase : caseInput.laneCases()) {
            outcomes.add(runLane(laneCase, lanes, timer, perLaneDeadline));
        }
        return new CaseVerdict(caseInput.fixturePath(), outcomes);
    }

    private static LaneOutcome runLane(LaneCase laneCase, Map<String, Lane> lanes,
            ScheduledExecutorService timer, Duration perLaneDeadline) {
        String backend = laneCase.backend();
        Lane lane = lanes.get(backend);
        if (lane == null) {
            // No silent lane omission (G6): an unregistered lane is an
            // infrastructure outcome that never satisfies the case.
            return new LaneOutcome(backend, Optional.of(new GateMismatch(
                MismatchClass.HARNESS_DEFECT, backend,
                "no lane implementation is registered for backend " + backend
                    + " — the gate never silently omits a lane")));
        }
        String laneName = lane.name();
        if (laneName == null || laneName.isEmpty()) {
            return new LaneOutcome(backend, Optional.of(new GateMismatch(
                MismatchClass.HARNESS_DEFECT, backend,
                "the lane implementation of backend " + backend
                    + " reports an empty lane name")));
        }
        if (!laneName.equals(backend)) {
            return new LaneOutcome(backend, Optional.of(new GateMismatch(
                MismatchClass.HARNESS_DEFECT, backend,
                "the lane implementation registered for backend " + backend
                    + " reports the name " + laneName)));
        }

        FutureTask<LaneExecution> task = new FutureTask<>(() -> lane.execute(laneCase));
        // The harness-owned deadline: the timer cancels the lane execution
        // (interrupting its thread — the lane contract requires the lane to
        // terminate its subprocess on interrupt) when it is exceeded.
        ScheduledFuture<?> timeout = timer.schedule(
            () -> task.cancel(true),
            perLaneDeadline.toMillis(), TimeUnit.MILLISECONDS);
        try {
            task.run(); // execute on the case worker thread
            LaneExecution execution = task.get();
            return new LaneOutcome(backend, StructuredExpectationComparator
                .compare(backend, laneCase.expectation(), execution));
        } catch (CancellationException e) {
            // Deadline exceeded: the execution was terminated. No retry.
            return new LaneOutcome(backend, Optional.of(new GateMismatch(
                MismatchClass.LANE_TIMEOUT, backend,
                "the lane exceeded the harness-owned deadline of "
                    + perLaneDeadline.toMillis() + " ms; the execution was "
                    + "terminated — the gate never retries a lane")));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new LaneOutcome(backend, Optional.of(new GateMismatch(
                MismatchClass.HARNESS_DEFECT, backend,
                "the lane threw an unexpected exception: "
                    + cause.getClass().getSimpleName() + ": "
                    + cause.getMessage())));
        } catch (InterruptedException e) {
            // The gate itself is shutting down: re-assert and report.
            Thread.currentThread().interrupt();
            return new LaneOutcome(backend, Optional.of(new GateMismatch(
                MismatchClass.HARNESS_DEFECT, backend,
                "the case worker was interrupted before the lane completed")));
        } finally {
            timeout.cancel(false);
            // A timed-out lane may have ignored its interrupt: clear the
            // residual flag so the next lane on this worker runs clean.
            Thread.interrupted();
        }
    }
}
