package deal.test.conformance;

/**
 * The differential gate's lane seam (design
 * {@code v12-zero-skip-conformance-gate} G4/G5): one backend
 * implementation executing one {@link LaneCase} through the real
 * production pipeline and returning the closed {@link LaneExecution}
 * outcome.
 *
 * <p>No lane implementations land in the gate-core child (ISSUE-0353);
 * the LuaJIT, JVM, and JS lanes land in the follow-up lane children
 * (T7-T9) and register here. The dispatch machinery — the G7 worker pool
 * and the harness-owned per-lane deadline — lives in
 * {@link GateDispatcher} and is exercised with test doubles only (each
 * double is flagged as such in the test code).</p>
 *
 * <p><b>Lane contract (G4):</b> compile the fixture (plus companions and
 * host triplets) with the real frontend; generate real backend artifacts
 * and assert their presence ({@link MismatchClass#ARTIFACT_MISSING}
 * otherwise); probe the lane's required tool
 * ({@link MismatchClass#TOOL_MISSING} otherwise); execute in a real
 * subprocess ({@code luajit}/{@code java}/{@code node}) and capture
 * stdout and stderr bytes separately; report a real execution with the
 * exact exit code, a compile rejection with the exact diagnostic object
 * (C6 {@code E6006} only), or an infrastructure outcome with bounded
 * detail. A lane MUST terminate its spawned subprocess(es) when its
 * execution thread is interrupted — the harness-owned deadline
 * enforcement depends on it; a lane that ignores the interrupt has
 * failed the contract and its outcome is
 * {@link MismatchClass#LANE_TIMEOUT}.</p>
 */
public interface Lane {

    /** The lane's backend name: exactly {@code luajit}, {@code jvm}, or {@code js}. */
    String name();

    /**
     * Executes one case and returns the closed lane outcome. Throwing is
     * a harness defect: the dispatcher wraps it as
     * {@link MismatchClass#HARNESS_DEFECT}.
     *
     * @param laneCase the dispatch unit (never null)
     * @return the lane outcome (never null)
     */
    LaneExecution execute(LaneCase laneCase) throws Exception;
}
